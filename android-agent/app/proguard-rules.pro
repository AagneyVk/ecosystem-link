# Keep serialization models
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.ecosystem.agent.**$$serializer { *; }
-keepclassmembers class com.ecosystem.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.ecosystem.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
