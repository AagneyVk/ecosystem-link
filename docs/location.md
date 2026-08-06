# Location

The milestone uses Android platform `LocationManager`, avoiding a Google Play Services dependency. It considers enabled GPS/network providers, requests a new fix with a 20-second timeout, never labels `lastKnownLocation` as current, and reports fix age/staleness, accuracy, provider, altitude, speed, bearing, and approximate/precise indication.

Permissions are contextual. Grant coarse or fine location in Android settings before testing. Verify separately with Location disabled, coarse only, GPS disabled, indoors/no fix, and an outdoor GPS fix. Live location requests at most one update per second and unregisters on stop or agent shutdown.
