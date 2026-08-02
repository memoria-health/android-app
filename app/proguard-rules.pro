# R8 / ProGuard keep rules.
#
# Referenced from app/build.gradle.kts. Empty of app-specific rules today
# because isMinifyEnabled = false, but the file exists deliberately: R8 treats a
# missing configuration file as a WARNING, not an error, so without it the build
# would succeed with no keep rules at all the moment minification is enabled.
#
# Rules will be needed here before release minification is switched on:
#   - kotlinx.serialization (Phase 2): R8 full mode strips generated serializers
#     unless the @Serializable classes and their .Companion serializer() methods
#     are kept. This fails at runtime in release builds only.
#   - LiveKit / WebRTC (Phase 4): native JNI callbacks are invoked reflectively.
#   - Any model class reached only by reflection.
#
# Do not enable minification without adding these and testing a release build
# end to end.
