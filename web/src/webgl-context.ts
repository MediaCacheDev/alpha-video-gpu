/**
 * Shared WebGL Context Manager
 *
 * Browsers limit WebGL contexts to ~8-16 per page. To support many players,
 * we share a single offscreen WebGL context and copy rendered frames to each
 * player's visible canvas using 2D context.
 */

import type { SharedGLResources } from './types';

let sharedGLResources: SharedGLResources | null = null;

// ---- Vertex/Fragment Shaders ----

const VERT_ARRAY = `#version 300 es
  in vec2 a_position;
  in vec2 a_texCoord;
  out vec2 v_texCoord;
  void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
    v_texCoord = a_texCoord;
  }
`;

const FRAG_ARRAY = `#version 300 es
  precision mediump float;
  precision mediump sampler2DArray;
  in vec2 v_texCoord;
  out vec4 fragColor;
  uniform sampler2DArray u_textureArray;
  uniform float u_layer;
  void main() {
    fragColor = texture(u_textureArray, vec3(v_texCoord, u_layer));
  }
`;

// ---- Geometry ----

const QUAD_POSITIONS = new Float32Array([
  -1, -1,  1, -1,  -1, 1,
  -1,  1,  1, -1,   1, 1,
]);

const QUAD_TEXCOORDS = new Float32Array([
  0, 1,  1, 1,  0, 0,
  0, 0,  1, 1,  1, 0,
]);

// ---- Helpers ----

function compileShader(
  gl: WebGLRenderingContext, type: number, source: string
): WebGLShader {
  const shader = gl.createShader(type)!;
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const info = gl.getShaderInfoLog(shader);
    gl.deleteShader(shader);
    throw new Error(`Shader compile error: ${info}`);
  }
  return shader;
}

function linkProgram(
  gl: WebGLRenderingContext, vs: WebGLShader, fs: WebGLShader
): WebGLProgram {
  const program = gl.createProgram()!;
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const info = gl.getProgramInfoLog(program);
    gl.deleteProgram(program);
    throw new Error(`Program link error: ${info}`);
  }
  return program;
}

// ---- Public API ----

/**
 * Ensure shared canvas is large enough for the given dimensions.
 * Only grows, never shrinks (to avoid thrashing with different resolutions).
 */
export function ensureSharedCanvasSize(width: number, height: number): void {
  if (!sharedGLResources) return;

  const needsResize =
    width > sharedGLResources.maxWidth || height > sharedGLResources.maxHeight;
  if (!needsResize) return;

  const newWidth = Math.max(sharedGLResources.maxWidth, width);
  const newHeight = Math.max(sharedGLResources.maxHeight, height);
  sharedGLResources.canvas.width = newWidth;
  sharedGLResources.canvas.height = newHeight;
  sharedGLResources.maxWidth = newWidth;
  sharedGLResources.maxHeight = newHeight;
  console.debug(`[AlphaVideoGpu] Shared canvas resized to ${newWidth}x${newHeight}`);
}

/**
 * Get (or create) the shared WebGL context. Increments refCount.
 * Returns null if WebGL is not available.
 */
export function getSharedGL(): SharedGLResources | null {
  if (sharedGLResources) {
    sharedGLResources.refCount++;
    return sharedGLResources;
  }

  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 512;

  const gl = (canvas.getContext('webgl2', {
    alpha: true,
    premultipliedAlpha: true,
    preserveDrawingBuffer: true,
  }) || canvas.getContext('webgl', {
    alpha: true,
    premultipliedAlpha: true,
    preserveDrawingBuffer: true,
  })) as WebGL2RenderingContext | WebGLRenderingContext | null;

  if (!gl) {
    console.warn('[AlphaVideoGpu] Failed to create shared WebGL context');
    return null;
  }

  // Compile texture array program (WebGL2 only)
  let arrayProgram: WebGLProgram | null = null;
  if (gl instanceof WebGL2RenderingContext) {
    try {
      const vsArr = compileShader(gl, gl.VERTEX_SHADER, VERT_ARRAY);
      const fsArr = compileShader(gl, gl.FRAGMENT_SHADER, FRAG_ARRAY);
      arrayProgram = linkProgram(gl, vsArr, fsArr);
    } catch (e) {
      console.warn('[AlphaVideoGpu] Texture array shader failed:', e);
    }
  }

  // Create geometry buffers
  const positionBuffer = gl.createBuffer()!;
  gl.bindBuffer(gl.ARRAY_BUFFER, positionBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, QUAD_POSITIONS, gl.STATIC_DRAW);

  const texCoordBuffer = gl.createBuffer()!;
  gl.bindBuffer(gl.ARRAY_BUFFER, texCoordBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, QUAD_TEXCOORDS, gl.STATIC_DRAW);

  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

  sharedGLResources = {
    gl,
    canvas,
    arrayProgram,
    positionBuffer,
    texCoordBuffer,
    refCount: 1,
    maxWidth: 512,
    maxHeight: 512,
  };

  console.debug('[AlphaVideoGpu] Created shared WebGL context');
  return sharedGLResources;
}

/** Decrement refCount and destroy shared context when no longer used. */
export function releaseSharedGL(): void {
  if (!sharedGLResources) return;

  sharedGLResources.refCount--;
  if (sharedGLResources.refCount <= 0) {
    const { gl, arrayProgram, positionBuffer, texCoordBuffer } =
      sharedGLResources;
    if (arrayProgram) gl.deleteProgram(arrayProgram);
    gl.deleteBuffer(positionBuffer);
    gl.deleteBuffer(texCoordBuffer);
    const ext = gl.getExtension('WEBGL_lose_context');
    if (ext) ext.loseContext();
    sharedGLResources = null;
    console.debug('[AlphaVideoGpu] Released shared WebGL context');
  }
}

/** Get the current shared resources (without incrementing refCount). */
export function getSharedGLResources(): SharedGLResources | null {
  return sharedGLResources;
}

/**
 * Create a dedicated (non-shared) GL context with compiled programs and buffers.
 * Used as fallback when the shared context's 2D copy path isn't available.
 */
export function createDedicatedGL(
  canvas: HTMLCanvasElement
): {
  gl: WebGL2RenderingContext | WebGLRenderingContext;
  arrayProgram: WebGLProgram | null;
  positionBuffer: WebGLBuffer;
  texCoordBuffer: WebGLBuffer;
} | null {
  const gl = (canvas.getContext('webgl2') ||
    canvas.getContext('webgl')) as
    | WebGL2RenderingContext
    | WebGLRenderingContext
    | null;

  if (!gl) return null;

  let arrayProgram: WebGLProgram | null = null;
  if (gl instanceof WebGL2RenderingContext) {
    try {
      const vsArr = compileShader(gl, gl.VERTEX_SHADER, VERT_ARRAY);
      const fsArr = compileShader(gl, gl.FRAGMENT_SHADER, FRAG_ARRAY);
      arrayProgram = linkProgram(gl, vsArr, fsArr);
    } catch {
      // WebGL2 array shaders not supported
    }
  }

  const positionBuffer = gl.createBuffer()!;
  gl.bindBuffer(gl.ARRAY_BUFFER, positionBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, QUAD_POSITIONS, gl.STATIC_DRAW);

  const texCoordBuffer = gl.createBuffer()!;
  gl.bindBuffer(gl.ARRAY_BUFFER, texCoordBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, QUAD_TEXCOORDS, gl.STATIC_DRAW);

  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

  return { gl, arrayProgram, positionBuffer, texCoordBuffer };
}
