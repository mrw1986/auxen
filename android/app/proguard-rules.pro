# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class io.github.auxen.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.auxen.** {
    kotlinx.serialization.KSerializer serializer(...);
}
