import type { ViewProps, HostComponent } from 'react-native';
import { requireNativeComponent } from 'react-native';

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
  playbackSpeed?: number;
  cacheKey?: string;
}

export default requireNativeComponent<NativeProps>(
  'KTX2PlayerView'
) as HostComponent<NativeProps>;
