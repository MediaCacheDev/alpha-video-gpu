import type { ViewProps, HostComponent } from 'react-native';
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';

import type {
  Float
} from 'react-native/Libraries/Types/CodegenTypes';

export interface SourceType {
  uri?: string;
  cacheKey?: string;
}

export interface NativeProps extends ViewProps {
  source?: SourceType | null;
  /** For multi-part animations: array of all part sources (first part contains metadata) */
  parts?: ReadonlyArray<SourceType> | null;
  autoPlay?: boolean;
  loop?: boolean;
  playbackSpeed?: Float;
  cacheKey?: string;
}

// codegenNativeComponent uses Fabric on New Architecture
// and auto-falls back to requireNativeComponent on Legacy Architecture
export default codegenNativeComponent<NativeProps>(
  'KTX2PlayerView'
) as HostComponent<NativeProps>;
