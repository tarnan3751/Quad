# R8 rules for the release and benchmark builds. Libraries ship their own consumer rules (kotlinx.serialization, Coil,
# Compose, Ktor, OkHttp); only what they miss is here.

# Stack traces stay readable with the mapping file (build/outputs/mapping/<type>/mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
