import { type ConfigPlugin } from '@expo/config-plugins';
export interface VonagePluginiOSProps {
    /**
     * iOS camera permission message
     * @default 'Allow $(PRODUCT_NAME) to access your camera for video calls'
     */
    cameraPermission?: string;
    /**
     * iOS microphone permission message
     * @default 'Allow $(PRODUCT_NAME) to access your microphone for audio calls'
     */
    microphonePermission?: string;
}
/**
 * Main Expo Config Plugin for @vonage/client-sdk-video-react-native
 * Automatically configures native permissions and dependencies
 */
declare const withVonage: ConfigPlugin<VonagePluginiOSProps>;
export default withVonage;
//# sourceMappingURL=index.d.ts.map