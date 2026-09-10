package com.copa.echo;

public class SaidIt {

    static final String PACKAGE_NAME = "com.copa.echo";
    static final String AUDIO_MEMORY_ENABLED_KEY = "audio_memory_enabled";
    static final String AUDIO_MEMORY_SIZE_KEY = "audio_memory_size";
    static final String SAMPLE_RATE_KEY = "sample_rate";
    static final String AUTO_SAVE_ENABLED_KEY = "auto_save_enabled";
    static final String AUTO_SAVE_INTERVAL_KEY = "auto_save_interval_minutes";
    static final int AUTO_SAVE_INTERVAL_DEFAULT = 5;
    static final String LOW_POWER_KEY = "low_power";
    static final String GPS_ENABLED_KEY = "gps_enabled";
    static final String PRE_LOW_POWER_SAMPLE_RATE_KEY = "pre_low_power_sample_rate";
    /** Record a clip from both cameras whenever the phone is shaken and then settles again. */
    static final String CAMERA_ENABLED_KEY = "camera_capture_enabled";
    /** How hard the shake has to be, as an index: 0 gentle, 1 normal, 2 vigorous. */
    static final String SHAKE_LEVEL_KEY = "shake_level";
    static final int SHAKE_LEVEL_DEFAULT = 1;
    /** Whether the front camera takes part at all, or only the back one does. */
    static final String CAMERA_FRONT_ENABLED_KEY = "camera_front_enabled";
    /** How many seconds each clip lasts. */
    static final String CLIP_SECONDS_KEY = "camera_clip_seconds";
    static final int CLIP_SECONDS_DEFAULT = 3;
    /** Shortest seconds between two captures, kept apart for each of the three kinds. */
    static final String CAMERA_MIN_BACK_KEY = "camera_min_back_seconds";
    static final String CAMERA_MIN_FRONT_KEY = "camera_min_front_seconds";
    static final String SCREENSHOT_MIN_KEY = "screenshot_min_seconds";
    static final int CAMERA_MIN_BACK_DEFAULT = 8;
    static final int CAMERA_MIN_FRONT_DEFAULT = 8;
    static final int SCREENSHOT_MIN_DEFAULT = 60;
    /** Listen for a spoken word in the captured audio and sound an alert when it is heard. */
    static final String KEYWORD_ENABLED_KEY = "keyword_enabled";
    static final String KEYWORD_WORDS_KEY = "keyword_words";
    static final String KEYWORD_WORDS_DEFAULT = "móvil";
    /** Directory inside assets holding the offline speech model the detector needs. */
    static final String KEYWORD_MODEL_ASSET = "vosk-model";
    /** Send saved traces to a server and delete them once accepted. */
    static final String UPLOAD_ENABLED_KEY = "upload_enabled";
    static final String UPLOAD_URL_KEY = "upload_url";
    /** Sample rate low power mode drops to: enough for speech, a sixth of the data of 48 kHz. */
    static final int LOW_POWER_SAMPLE_RATE = 8000;
    /** How often a location fix is asked for while logging. */
    static final long GPS_INTERVAL_MILLIS = 5000;
    /** Low power mode wants the GPS radio awake as rarely as the microphone. */
    static final long LOW_POWER_GPS_INTERVAL_MILLIS = 30000;
    static final String SKU = "unlimited_history";
    static final String BASE64_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlD0FMFGp4AWzjW" +
            "LTsUZgm0soga0mVVNGFj0qoATaoQCE/LamF7yrMCIFm9sEOB1guCEhzdr16sjysrVc2EPRisS83FoJ4K0R8" +
            "XPDP2TrVT2SAeQpTCG27NNH+W86SlGEqQeQhMPMhR+HDTckHv3KBpD8BZEEIbkXPv6SGFqcZub6xzn9r14l" +
            "6ptYIWboKGGBh1i9/nJpdhCMPxuLn/WZnRXGxqGpfNw2xT25/muUDZgRVezy6/5eI+ciMn5H1U0ADBjXvl1" +
            "Py+4ClkR1V1Mfo9lvauB03zM8Fsa3LlIPle5a+wGKsRCLW/rJ/eE/rje6X7x/n+w8J4OiFvVATj0T8QIDAQ" +
            "AB";

}
