# JNI resolves these classes, fields and callback methods by their original
# names. The dependency's consumer rules are empty; R8 cannot see native use.
-keep class net.sf.sevenzipjbinding.** { *; }
-keep interface net.sf.sevenzipjbinding.** { *; }
-keep class * implements net.sf.sevenzipjbinding.** { *; }
