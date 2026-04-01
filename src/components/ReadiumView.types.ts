import type {
  Preferences,
  Locator,
  File,
  DecorationGroup,
  SelectionAction,
  PublicationReadyEvent,
  DecorationActivatedEvent,
  SelectionEvent,
  SelectionActionEvent,
  TTSConfig,
  TTSState,
  TTSUtteranceEvent,
} from '../interfaces';

export type ReadiumViewRef = {
  goTo: (locator: Locator) => void;
  goForward: () => void;
  goBackward: () => void;
  ttsStart: (config?: TTSConfig) => void;
  ttsStop: () => void;
  ttsPause: () => void;
  ttsResume: () => void;
  ttsSetRate: (rate: number) => void;
  ttsSkipNext: () => void;
  ttsSkipPrevious: () => void;
};

export type ReadiumProps = {
  file: File;
  preferences: Preferences;
  decorations?: DecorationGroup[];
  selectionActions?: SelectionAction[];
  suppressNativeSelectionMenu?: boolean;
  style?: any;
  onLocationChange?: (locator: Locator) => void;
  onPublicationReady?: (event: PublicationReadyEvent) => void;
  onDecorationActivated?: (event: DecorationActivatedEvent) => void;
  onSelectionChange?: (event: SelectionEvent) => void;
  onSelectionAction?: (event: SelectionActionEvent) => void;
  onTTSStateChange?: (state: TTSState) => void;
  onTTSUtterance?: (event: TTSUtteranceEvent) => void;
  onTTSError?: (error: string) => void;
};
