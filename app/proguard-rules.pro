# Najim WiFi: targeted production rules. Keep optimization enabled.
# Android/Room/WorkManager/Google dependencies contribute their own rules.

# Room may resolve the generated database implementation by its exact name.
-keep class com.najimwifi.app.data.local.NajimDatabase_Impl { *; }

# The system instantiates the app's FileProvider from AndroidManifest.xml.
-keep class com.najimwifi.app.data.report.NajimPdfProvider {
    public <init>();
}

# WorkManager instantiates worker classes by name after process death.
-keep class com.najimwifi.app.backup.BackupWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep line information for owner-held retrace mapping files.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Do NOT use -dontwarn or broad -keep class com.najimwifi.app.** here.
# Any R8 missing-class diagnostics require a specific dependency/rule review.
