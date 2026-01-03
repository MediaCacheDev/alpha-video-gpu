/**
 * KTX2PlayerView - React Native Web Implementation
 * 
 * Uses WebGL and Basis Universal transcoder to play KTX2 animations
 * Same API as native iOS/Android implementations
 */

import React, { useRef, useEffect, useCallback, useState, useMemo } from 'react';
import { View, StyleSheet, Platform } from 'react-native';

// Basis Universal transcoder types
declare global {
  interface Window {
    BASIS: any;
  }
  var BASIS: any;
}

export interface SourceType {
  uri?: string;
  cacheKey?: string;
}

export interface KTX2PlayerViewProps {
  source?: SourceType | string | null;
  /** For multi-part animations: array of all part sources (first part contains metadata) */
  parts?: SourceType[] | null;
  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: number;
  cacheKey?: string;
  style?: any;
  onStateChange?: (state: string) => void;
  onFrameUpdate?: (frame: number, total: number) => void;
  onFinished?: () => void;
  onError?: (error: Error) => void;
}

interface FrameUV {
  minU: number;
  minV: number;
  maxU: number;
  maxV: number;
}

interface Metadata {
  frameCount: number;
  frameWidth: number;
  frameHeight: number;
  frameRate: number;
  framesPerRow: number;
  framesPerPart?: number;
  framesPerStrip?: number;
  partCount?: number;
  stripCount?: number;
  parts?: Array<{ file: string; columns: number; rows: number }>;
  strips?: Array<{ file: string; columns: number; rows: number }>;
  mode?: string;
}

/**
 * Extract BasisPlayerMetadata from KTX2 key-value data section.
 * Returns null if not found.
 */
function extractKTX2Metadata(ktx2Data: Uint8Array): Metadata | null {
  try {
    const view = new DataView(ktx2Data.buffer, ktx2Data.byteOffset, ktx2Data.byteLength);
    
    // Check KTX2 magic
    if (ktx2Data[0] !== 0xAB || ktx2Data[1] !== 0x4B || ktx2Data[2] !== 0x54 || ktx2Data[3] !== 0x58) {
      return null; // Not KTX2
    }
    
    // Read KVD offset and length from header (bytes 56-63)
    const kvdByteOffset = view.getUint32(56, true);
    const kvdByteLength = view.getUint32(60, true);
    
    if (kvdByteLength === 0) {
      return null; // No key-value data
    }
    
    // Parse key-value entries
    let pos = kvdByteOffset;
    const kvdEnd = kvdByteOffset + kvdByteLength;
    
    while (pos < kvdEnd) {
      // Read entry length
      const entryLength = view.getUint32(pos, true);
      pos += 4;
      
      if (entryLength === 0) break;
      
      // Find key (NUL-terminated string)
      let keyEnd = pos;
      while (keyEnd < pos + entryLength && ktx2Data[keyEnd] !== 0) {
        keyEnd++;
      }
      
      const key = new TextDecoder().decode(ktx2Data.slice(pos, keyEnd));
      
      // Value starts after key's NUL terminator
      const valueStart = keyEnd + 1;
      const valueEnd = pos + entryLength;
      
      // Find actual value end (may have NUL terminator)
      let actualValueEnd = valueEnd;
      while (actualValueEnd > valueStart && ktx2Data[actualValueEnd - 1] === 0) {
        actualValueEnd--;
      }
      
      if (key === 'BasisPlayerMetadata') {
        const valueStr = new TextDecoder().decode(ktx2Data.slice(valueStart, actualValueEnd));
        return JSON.parse(valueStr);
      }
      
      // Move to next entry (with padding to 4-byte alignment)
      pos += entryLength;
      pos += (4 - (entryLength % 4)) % 4;
    }
    
    return null; // Key not found
  } catch (error) {
    console.warn('[KTX2PlayerView] Failed to extract KTX2 metadata:', error);
    return null;
  }
}

// Singleton for Basis module
let basisModulePromise: Promise<any> | null = null;
let basisModule: any = null;

async function initBasisTranscoder(): Promise<any> {
  if (basisModule) return basisModule;
  
  if (basisModulePromise) return basisModulePromise;
  
  basisModulePromise = (async () => {
    // Wait for BASIS to be available
    let attempts = 0;
    while (typeof BASIS === 'undefined' && attempts < 100) {
      await new Promise(resolve => setTimeout(resolve, 50));
      attempts++;
    }
    
    if (typeof BASIS === 'undefined') {
      throw new Error('Basis Universal not loaded. Include basis_transcoder.js script.');
    }
    
    const module = await BASIS();
    module.initializeBasis();
    basisModule = module;
    return module;
  })();
  
  return basisModulePromise;
}

export const KTX2PlayerView: React.FC<KTX2PlayerViewProps> = ({
  source,
  parts,
  autoPlay = true,
  loop = true,
  playbackSpeed = 1.0,
  style,
  onStateChange,
  onFrameUpdate,
  onFinished,
  onError,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const glRef = useRef<WebGLRenderingContext | WebGL2RenderingContext | null>(null);
  const programRef = useRef<WebGLProgram | null>(null);
  const positionBufferRef = useRef<WebGLBuffer | null>(null);
  const texCoordBufferRef = useRef<WebGLBuffer | null>(null);
  const texturesRef = useRef<WebGLTexture[]>([]);
  const frameUVsRef = useRef<FrameUV[]>([]);
  const metadataRef = useRef<Metadata | null>(null);
  const animationFrameRef = useRef<number | null>(null);
  const lastFrameTimeRef = useRef<number>(0);
  const currentFrameRef = useRef<number>(0);
  const stateRef = useRef<string>('idle');
  
  const [state, setState] = useState<string>('idle');
  
  // Normalize source
  const normalizedSource = useMemo(() => {
    if (!source) return null;
    if (typeof source === 'object') return source;
    return { uri: source };
  }, [source]);

  const setPlayerState = useCallback((newState: string) => {
    if (stateRef.current === newState) return;
    stateRef.current = newState;
    setState(newState);
    onStateChange?.(newState);
  }, [onStateChange]);

  // Initialize WebGL
  const initGL = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    
    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
    if (!gl) {
      onError?.(new Error('WebGL not supported'));
      return null;
    }
    
    // Vertex shader
    const vertexShaderSource = `
      attribute vec2 a_position;
      attribute vec2 a_texCoord;
      varying vec2 v_texCoord;
      void main() {
        gl_Position = vec4(a_position, 0.0, 1.0);
        v_texCoord = a_texCoord;
      }
    `;
    
    // Fragment shader
    const fragmentShaderSource = `
      precision mediump float;
      varying vec2 v_texCoord;
      uniform sampler2D u_texture;
      uniform vec4 u_frameUV;
      void main() {
        vec2 uv = mix(u_frameUV.xy, u_frameUV.zw, v_texCoord);
        gl_FragColor = texture2D(u_texture, uv);
      }
    `;
    
    const vertexShader = gl.createShader(gl.VERTEX_SHADER)!;
    gl.shaderSource(vertexShader, vertexShaderSource);
    gl.compileShader(vertexShader);
    
    const fragmentShader = gl.createShader(gl.FRAGMENT_SHADER)!;
    gl.shaderSource(fragmentShader, fragmentShaderSource);
    gl.compileShader(fragmentShader);
    
    const program = gl.createProgram()!;
    gl.attachShader(program, vertexShader);
    gl.attachShader(program, fragmentShader);
    gl.linkProgram(program);
    
    // Create buffers
    const positions = new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]);
    const positionBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, positionBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, positions, gl.STATIC_DRAW);
    
    const texCoords = new Float32Array([0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 0]);
    const texCoordBuffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, texCoordBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, texCoords, gl.STATIC_DRAW);
    
    gl.enable(gl.BLEND);
    // Use premultiplied alpha blending (RGB is pre-multiplied by alpha in encoder)
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    
    glRef.current = gl;
    programRef.current = program;
    positionBufferRef.current = positionBuffer;
    texCoordBufferRef.current = texCoordBuffer;
    
    return gl;
  }, [onError]);

  // Detect best texture format
  const detectTextureFormat = useCallback((gl: WebGLRenderingContext, BasisModule: any) => {
    const formats = [
      { ext: 'WEBGL_compressed_texture_astc', format: BasisModule.cTFASTC_4x4_RGBA },
      { ext: 'WEBGL_compressed_texture_s3tc', format: BasisModule.cTFBC3_RGBA },
      { ext: 'WEBGL_compressed_texture_etc', format: BasisModule.cTFETC2_RGBA },
      { ext: 'WEBGL_compressed_texture_pvrtc', format: BasisModule.cTFPVRTC1_4_RGBA },
    ];
    
    for (const { ext, format } of formats) {
      if (gl.getExtension(ext)) {
        return format;
      }
    }
    
    return BasisModule.cTFRGBA32;
  }, []);

  // Load native ASTC KTX2 (no transcoding needed)
  const loadNativeASTCKTX2 = useCallback(async (
    gl: WebGLRenderingContext,
    data: Uint8Array
  ): Promise<WebGLTexture | null> => {
    // Check for ASTC support - required for native ASTC textures
    const astcExt = gl.getExtension('WEBGL_compressed_texture_astc');
    if (!astcExt) {
      throw new Error(
        'ASTC texture compression not supported by this browser/device. ' +
        'Native ASTC KTX2 files require hardware ASTC support. ' +
        'Use UASTC encoding for universal compatibility, or test on a device with ASTC support.'
      );
    }
    
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    
    // Parse KTX2 header
    const vkFormat = view.getUint32(12, true);
    const pixelWidth = view.getUint32(20, true);
    const pixelHeight = view.getUint32(24, true);
    const supercompressionScheme = view.getUint32(44, true);
    
    // Map Vulkan format to WebGL ASTC format
    const ASTC_FORMAT_MAP: Record<number, number> = {
      157: 0x93B0, // COMPRESSED_RGBA_ASTC_4x4_KHR
      158: 0x93D0, // COMPRESSED_SRGB8_ALPHA8_ASTC_4x4_KHR
      159: 0x93B1, 160: 0x93D1, 161: 0x93B2, 162: 0x93D2,
      163: 0x93B3, 164: 0x93D3, 165: 0x93B4, 166: 0x93D4,
      167: 0x93B5, 168: 0x93D5, 169: 0x93B6, 170: 0x93D6,
      171: 0x93B7, 172: 0x93D7, 173: 0x93B8, 174: 0x93D8,
      175: 0x93B9, 176: 0x93D9, 177: 0x93BA, 178: 0x93DA,
      179: 0x93BB, 180: 0x93DB, 181: 0x93BC, 182: 0x93DC,
      183: 0x93BD, 184: 0x93DD,
    };
    
    const glFormat = ASTC_FORMAT_MAP[vkFormat];
    if (!glFormat) {
      throw new Error(`Unsupported ASTC format: VkFormat ${vkFormat}`);
    }
    
    // Read level index (after 80-byte header)
    const levelIndexOffset = 80;
    const level0ByteOffset = Number(view.getBigUint64(levelIndexOffset, true));
    const level0ByteLength = Number(view.getBigUint64(levelIndexOffset + 8, true));
    
    // Handle supercompression (Zstandard)
    let textureData: Uint8Array;
    if (supercompressionScheme === 2) {
      // Zstandard compression - need fzstd library
      if (typeof (window as any).fzstd === 'undefined') {
        throw new Error('Zstandard decompression required but fzstd not loaded.');
      }
      const compressedData = new Uint8Array(data.buffer, data.byteOffset + level0ByteOffset, level0ByteLength);
      textureData = (window as any).fzstd.decompress(compressedData);
    } else if (supercompressionScheme === 0) {
      textureData = new Uint8Array(data.buffer, data.byteOffset + level0ByteOffset, level0ByteLength);
    } else {
      throw new Error(`Unsupported supercompression scheme: ${supercompressionScheme}`);
    }
    
    // Create and upload compressed texture
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.compressedTexImage2D(gl.TEXTURE_2D, 0, glFormat, pixelWidth, pixelHeight, 0, textureData);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    
    return texture;
  }, []);

  // Transcode KTX2 data (UASTC)
  const transcodeKTX2 = useCallback(async (
    gl: WebGLRenderingContext,
    BasisModule: any,
    data: Uint8Array,
    targetFormat: number
  ): Promise<WebGLTexture | null> => {
    const ktx2File = new BasisModule.KTX2File(data);
    
    if (!ktx2File.isValid()) {
      ktx2File.close();
      ktx2File.delete();
      throw new Error('Invalid KTX2 file');
    }
    
    if (!ktx2File.startTranscoding()) {
      ktx2File.close();
      ktx2File.delete();
      throw new Error('Failed to start transcoding');
    }
    
    const width = ktx2File.getWidth();
    const height = ktx2File.getHeight();
    const levels = ktx2File.getLevels();
    const hasAlpha = ktx2File.getHasAlpha();
    
    const texture = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, texture);
    
    for (let level = 0; level < levels; level++) {
      const dstSize = ktx2File.getImageTranscodedSizeInBytes(level, 0, 0, targetFormat);
      const dst = new Uint8Array(dstSize);
      
      if (!ktx2File.transcodeImage(dst, level, 0, 0, targetFormat, 0, -1, -1)) {
        ktx2File.close();
        ktx2File.delete();
        throw new Error(`Failed to transcode level ${level}`);
      }
      
      const levelWidth = Math.max(1, width >> level);
      const levelHeight = Math.max(1, height >> level);
      
      if (targetFormat === BasisModule.cTFRGBA32) {
        gl.texImage2D(gl.TEXTURE_2D, level, gl.RGBA, levelWidth, levelHeight, 0, gl.RGBA, gl.UNSIGNED_BYTE, dst);
      } else {
        const glFormat = getGLCompressedFormat(gl, targetFormat, hasAlpha);
        gl.compressedTexImage2D(gl.TEXTURE_2D, level, glFormat, levelWidth, levelHeight, 0, dst);
      }
    }
    
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, levels > 1 ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    
    ktx2File.close();
    ktx2File.delete();
    
    return texture;
  }, []);

  // Calculate frame UVs
  const calculateFrameUVs = useCallback((metadata: Metadata) => {
    const uvs: FrameUV[] = [];
    const { frameCount, frameWidth, frameHeight, framesPerRow } = metadata;
    const framesPerPart = metadata.framesPerPart || metadata.framesPerStrip || frameCount;
    const parts = metadata.parts || metadata.strips || [];
    
    for (let i = 0; i < frameCount; i++) {
      const partIndex = Math.floor(i / framesPerPart);
      const frameInPart = i % framesPerPart;
      const col = frameInPart % framesPerRow;
      const row = Math.floor(frameInPart / framesPerRow);
      
      let partWidth: number, partHeight: number;
      if (parts[partIndex]) {
        partWidth = parts[partIndex].columns * frameWidth;
        partHeight = parts[partIndex].rows * frameHeight;
      } else {
        partWidth = framesPerRow * frameWidth;
        const rowsInPart = Math.ceil(framesPerPart / framesPerRow);
        partHeight = rowsInPart * frameHeight;
      }
      
      uvs.push({
        minU: (col * frameWidth) / partWidth,
        minV: (row * frameHeight) / partHeight,
        maxU: ((col + 1) * frameWidth) / partWidth,
        maxV: ((row + 1) * frameHeight) / partHeight,
      });
    }
    
    return uvs;
  }, []);

  // Render frame
  const render = useCallback(() => {
    const gl = glRef.current;
    const program = programRef.current;
    const frameUVs = frameUVsRef.current;
    const textures = texturesRef.current;
    const metadata = metadataRef.current;
    
    if (!gl || !program || !frameUVs.length || !textures.length || !metadata) return;
    
    const currentFrame = currentFrameRef.current;
    const frameUV = frameUVs[currentFrame];
    if (!frameUV) return;
    
    // Determine which texture to use
    const framesPerPart = metadata.framesPerPart || metadata.framesPerStrip || metadata.frameCount;
    const partIndex = Math.floor(currentFrame / framesPerPart);
    const texture = textures[partIndex] || textures[0];
    
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.useProgram(program);
    
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.uniform1i(gl.getUniformLocation(program, 'u_texture'), 0);
    gl.uniform4f(gl.getUniformLocation(program, 'u_frameUV'), frameUV.minU, frameUV.minV, frameUV.maxU, frameUV.maxV);
    
    const positionLoc = gl.getAttribLocation(program, 'a_position');
    gl.bindBuffer(gl.ARRAY_BUFFER, positionBufferRef.current);
    gl.enableVertexAttribArray(positionLoc);
    gl.vertexAttribPointer(positionLoc, 2, gl.FLOAT, false, 0, 0);
    
    const texCoordLoc = gl.getAttribLocation(program, 'a_texCoord');
    gl.bindBuffer(gl.ARRAY_BUFFER, texCoordBufferRef.current);
    gl.enableVertexAttribArray(texCoordLoc);
    gl.vertexAttribPointer(texCoordLoc, 2, gl.FLOAT, false, 0, 0);
    
    gl.drawArrays(gl.TRIANGLES, 0, 6);
  }, []);

  // Animation loop
  const startAnimationLoop = useCallback(() => {
    if (animationFrameRef.current) return;
    
    const metadata = metadataRef.current;
    if (!metadata) return;
    
    const frameInterval = 1000 / metadata.frameRate;
    
    const animate = (timestamp: number) => {
      if (stateRef.current !== 'playing') return;
      
      const elapsed = timestamp - lastFrameTimeRef.current;
      
      if (elapsed >= frameInterval / playbackSpeed) {
        lastFrameTimeRef.current = timestamp - (elapsed % (frameInterval / playbackSpeed));
        
        render();
        onFrameUpdate?.(currentFrameRef.current, metadata.frameCount);
        
        currentFrameRef.current++;
        
        if (currentFrameRef.current >= metadata.frameCount) {
          if (loop) {
            currentFrameRef.current = 0;
          } else {
            setPlayerState('stopped');
            onFinished?.();
            return;
          }
        }
      }
      
      animationFrameRef.current = requestAnimationFrame(animate);
    };
    
    animationFrameRef.current = requestAnimationFrame(animate);
  }, [loop, playbackSpeed, render, setPlayerState, onFrameUpdate, onFinished]);

  const stopAnimationLoop = useCallback(() => {
    if (animationFrameRef.current) {
      cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }
  }, []);

  // Load source
  const loadSource = useCallback(async (src: SourceType) => {
    try {
      setPlayerState('loading');
      
      const gl = glRef.current || initGL();
      if (!gl) return;
      
      const BasisModule = await initBasisTranscoder();
      const targetFormat = detectTextureFormat(gl, BasisModule);
      
      // Fetch KTX2 file
      const uri = src.uri;
      if (!uri) throw new Error('No source URI provided');
      
      const response = await fetch(uri);
      if (!response.ok) throw new Error(`Failed to fetch: ${response.status}`);
      
      const arrayBuffer = await response.arrayBuffer();
      const data = new Uint8Array(arrayBuffer);
      
      // Load metadata from embedded KTX2 key-value data
      const metadata = extractKTX2Metadata(data);
      
      if (!metadata) {
        throw new Error('No metadata found in KTX2 file. Ensure the file was encoded with metadata injection.');
      }
      
      console.log('[KTX2PlayerView] Using embedded KTX2 metadata');
      metadataRef.current = metadata;
      
      // Clean up old textures
      texturesRef.current.forEach(tex => gl.deleteTexture(tex));
      texturesRef.current = [];
      
      // Load textures
      const partCount = metadata.partCount || metadata.stripCount || 1;
      
      // Check if this is native ASTC (no transcoding needed)
      const isNativeASTC = metadata.mode === 'astc';
      
      // Check if we have explicit parts from props
      const useExplicitParts = parts && parts.length > 1;
      const actualPartCount = useExplicitParts ? parts.length : partCount;
      
      if (actualPartCount > 1) {
        const metadataParts = metadata.parts || metadata.strips || [];
        const basePath = uri.replace(/(_\d+)?\.(ktx2|basis)$/i, '');
        
        // Load all parts
        for (let i = 0; i < actualPartCount; i++) {
          let partUrl: string;
          
          if (useExplicitParts && parts[i]?.uri) {
            // Use explicit URI from parts prop
            partUrl = parts[i].uri!;
          } else {
            // Use filename from metadata or construct from base path
            const partFile = metadataParts[i]?.file || `${basePath}_${i}.ktx2`;
            partUrl = partFile.startsWith('http') ? partFile : 
              uri.substring(0, uri.lastIndexOf('/') + 1) + partFile;
          }
          
          const partResponse = await fetch(partUrl);
          const partData = new Uint8Array(await partResponse.arrayBuffer());
          
          let texture: WebGLTexture | null;
          if (isNativeASTC) {
            texture = await loadNativeASTCKTX2(gl, partData);
          } else {
            texture = await transcodeKTX2(gl, BasisModule, partData, targetFormat);
          }
          if (texture) texturesRef.current.push(texture);
        }
      } else {
        let texture: WebGLTexture | null;
        if (isNativeASTC) {
          console.log('[KTX2PlayerView] Using native ASTC loader');
          texture = await loadNativeASTCKTX2(gl, data);
        } else {
          texture = await transcodeKTX2(gl, BasisModule, data, targetFormat);
        }
        if (texture) texturesRef.current.push(texture);
      }
      
      // Calculate UVs
      frameUVsRef.current = calculateFrameUVs(metadata);
      currentFrameRef.current = 0;
      
      setPlayerState('ready');
      render();
      
      if (autoPlay) {
        setPlayerState('playing');
        lastFrameTimeRef.current = performance.now();
        startAnimationLoop();
      }
      
    } catch (error) {
      console.error('[KTX2PlayerView] Load error:', error);
      setPlayerState('error');
      onError?.(error as Error);
    }
  }, [initGL, detectTextureFormat, transcodeKTX2, calculateFrameUVs, render, autoPlay, setPlayerState, startAnimationLoop, onError]);

  // Effect: Load source when it changes
  useEffect(() => {
    if (normalizedSource) {
      loadSource(normalizedSource);
    }
    
    return () => {
      stopAnimationLoop();
    };
  }, [normalizedSource, loadSource, stopAnimationLoop]);

  // Effect: Handle playback speed changes
  useEffect(() => {
    if (stateRef.current === 'playing') {
      stopAnimationLoop();
      startAnimationLoop();
    }
  }, [playbackSpeed, startAnimationLoop, stopAnimationLoop]);

  // Effect: Handle loop changes
  useEffect(() => {
    // Loop is handled in animation loop
  }, [loop]);

  // Cleanup
  useEffect(() => {
    return () => {
      stopAnimationLoop();
      const gl = glRef.current;
      if (gl) {
        texturesRef.current.forEach(tex => gl.deleteTexture(tex));
        if (programRef.current) gl.deleteProgram(programRef.current);
        if (positionBufferRef.current) gl.deleteBuffer(positionBufferRef.current);
        if (texCoordBufferRef.current) gl.deleteBuffer(texCoordBufferRef.current);
      }
    };
  }, [stopAnimationLoop]);

  // Get canvas dimensions from style
  const canvasStyle = useMemo(() => {
    const flatStyle = StyleSheet.flatten(style) || {};
    return {
      width: flatStyle.width || 300,
      height: flatStyle.height || 300,
    };
  }, [style]);

  return (
    <View style={[styles.container, style]}>
      <canvas
        ref={canvasRef}
        width={canvasStyle.width}
        height={canvasStyle.height}
        style={{ width: '100%', height: '100%' }}
      />
    </View>
  );
};

// Helper: Get GL compressed format
function getGLCompressedFormat(gl: WebGLRenderingContext, basisFormat: number, hasAlpha: boolean): number {
  // These constants come from WebGL extensions
  const COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
  const COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;
  const COMPRESSED_RGBA8_ETC2_EAC = 0x9278;
  const COMPRESSED_RGBA_PVRTC_4BPPV1_IMG = 0x8C02;
  
  // Map Basis format to GL format (simplified)
  // In practice, you'd check the actual format value
  const ext = gl.getExtension('WEBGL_compressed_texture_astc');
  if (ext) return COMPRESSED_RGBA_ASTC_4x4_KHR;
  
  const s3tc = gl.getExtension('WEBGL_compressed_texture_s3tc');
  if (s3tc) return COMPRESSED_RGBA_S3TC_DXT5_EXT;
  
  const etc = gl.getExtension('WEBGL_compressed_texture_etc');
  if (etc) return COMPRESSED_RGBA8_ETC2_EAC;
  
  const pvrtc = gl.getExtension('WEBGL_compressed_texture_pvrtc');
  if (pvrtc) return COMPRESSED_RGBA_PVRTC_4BPPV1_IMG;
  
  return gl.RGBA;
}

const styles = StyleSheet.create({
  container: {
    overflow: 'hidden',
  },
});

KTX2PlayerView.displayName = 'KTX2PlayerView';

export default KTX2PlayerView;
