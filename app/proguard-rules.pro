# M0: minify is off (see inkstone.android-application.gradle.kts).
#
# ROADMAP M10 owns turning R8 on and verifying it. The known hazard, recorded
# now: kotlinx-serialization uses reflection to find generated serializers, and
# a stripped @Serializable class fails at runtime with a message that does not
# name the class. When M10 arrives, test a release build against a real note
# round-trip, not just app launch.
