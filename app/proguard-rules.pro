# Created by name from XML (res/xml/preferences.xml and layout/activity_settings.xml), so R8 can't
# see that they're used.
-keep class com.tyllad.dkiosk.settings.TimePreference {
    <init>(android.content.Context, android.util.AttributeSet);
}
-keep class com.tyllad.dkiosk.settings.SettingsFragment {
    <init>();
}
