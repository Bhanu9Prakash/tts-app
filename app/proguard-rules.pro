# Keep the provider interfaces' implementations discoverable by name for the
# settings UI, which lists providers by their capability id.
-keepclassmembers class dev.voicecomposer.** {
    public <init>(...);
}

# Never keep source file names / line numbers in release: stack traces are
# attached to crash reports, and file/line detail is not worth the extra
# information disclosure given the app handles dictated text.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
