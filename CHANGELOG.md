# Blue Triangle 2.19.7 Latest, July 20 2026
### New Improvements
- SDK logging is not working in debug mode with previous change of BuildConfig.DEBUG.
- Make `androidx.navigation:navigation-compose` dependency compileOnly to avoid version conflict in consuming app.

# Blue Triangle 2.19.6, July 08 2026
### New Improvements
- Automatic screen tracking for Compose using btt-gradle-plugin plugin. Added support for btt-gradle-plugin to track Compose screens automatically.
- SDK logs are only emitted in DEBUG mode(BuildConfig.DEBUG).

# Blue Triangle 2.19.5, May 22 2026
### New Features
- App installs tracking: BlueTriangle can now track new app installs.
- App Force Restart Tracking: If the user force restarts the app, kills it, and restarts immediately, BlueTriangle tracks it as Force Restart. User has a tendency to force restart the app if something on the current screen is not working. BlueTriangle can now track this as an error.

### Bug Fixes and Improvements
- Disabling user tap interspersion for groups will also disable it in breadcrumbs.

# Blue Triangle 2.19.4, Mar 23 2026
### New Improvements
- Added breadcrumbs for ANRs, crashes, and memory warnings
- Added configKey to Launch time beacon

# Blue Triangle 2.19.3, Feb 18 2026
### New Improvements
- Added support for auto checkout reporting via remote configuration.
- Added eventID to the NATIVEAPP property for all event types.
- Added support for custom categories and additional parameters abTestID, campaignMedium, campaignName, campaignSource, and dataCenter.

# Blue Triangle 2.19.2, Jan 28 2026
### New Improvements
- Removed Redundant HITS from errors. Errors are now associated with the most recent timer if it exists.
- Unifying the sample rates for network and classes/fragments. Removed groupedViewSampleRate field.
- WCD flag to denote whether the session has WCD data
- Matching pageType/Traffic segment between error and HITS beacons.
- Improved memory warning message for consistency. 
- Improved ANR warning message for consistency

# Blue Triangle 2.19.1, Jan 5 2026
### New Improvements
- Added remote configuration flags to enable/disable following features:
  - Grouping tap detection
  - Network state tracking
  - ANR tracking
  - Crash tracking
  - Memory warning
  - Launch time tracking
  - WebView stitching.
- Changed default groupedViewSampleRate to 5%
- Fixed excessive Launch time values issue. In cases where process was already created in background due to a work manager, push notification, BroadcastReceiver, etc., the Launch time was showing excessive values in hours and days even.

# Blue Triangle 2.19.0 Latest, Dec 16 2025
### Improvements and Breaking Changes
- Updated minimum supported Android version to API 21 (minSdkVersion = 21).
- Optimized Performance Monitoring by moving CPU, Memory, and Main Thread usage tracking to a single global monitor instead of per-Timer threads.

# Blue Triangle 2.18.5, Nov 28 2025
### Bug Fixes
- Resolved rare crashes in NetworkStateMonitor occurring during ConnectivityManager.registerNetworkCallback() on Android 11 and above. These crashes resulted due to a limit to registerNetworkCallback in Android 11 and above Android versions. This release reduces the number of registerNetworkCallback calls from 3 down to 1 and also adds additional try-catch guard to further crash proof this feature.
- Fixed a low-frequency crash in Tracker.getMostRecentTimer(). The issue occurred in ArrayDeque.getLastOrNull() due to an internal state bug in Kotlin standard library that occurs under after some rare set of operations in Kotlin 1.8.x. Added Additional safety checks and synchronization improvements now prevent this crash.

# Blue Triangle 2.18.4, Nov 21 2025
### Bug Fixes and Improvements
- Lowercased cellular network states 2g, 3g, 4g, 5g
- Changed remote config URL to use site ID prefix
- Fixed network sample rate config parsing to accept decimal values e.g. 1.5%, 2.5%, etc.

# Blue Triangle 2.18.3, Nov 4 2025
### Bug Fixes
- Fixed a rare crash caused by a race condition during ActivityLifecycleTracker.unregister call.

# Blue Triangle 2.18.2, Oct 9 2025
### Bug Fixes
- WebView related occasional crash fixed. On configuration update, if WebView was already loaded and got a new configuration update, the app might crash.

# Blue Triangle 2.18.1, Sept 15 2025
### New Features
- Associating Performance data to Group children. Added 6 properties for minCPU, maxCPU, avgCPU, minMemory, maxMemory, avgMemory to WCD payload for group children.

# Blue Triangle 2.18.0, Sept 2 2025
### New Features
- Automated Grouping of all single-screen Activities/Fragments/Composables.
- Automated group name through screen title or class name.
- Manual API to start a new group with `setNewGroup(<Group name>)`.
- Manual API to provide group name with `setGroupName(<Group name>)` method.
- Added the ability to remotely enable or disable grouping through the remote configuration system, allowing dynamic control over this feature in real-time for tracking both Layout and Compose views. It is enabled by default.
- Calculation of page time for Group by doing a sum of all group controllers/views with discarding overlapped timings.
- Sending groupingCause field in NativeApp (timeout, tap, manual)
- Entry type sent as "screen" for controllers/views in WCD.

# Blue Triangle 2.17.2, July 30 2025
### New Features
- Added the ability to remotely enable or disable screen tracking through the remote configuration system, allowing dynamic control over this feature in real-time for tracking both Compose and Layout views.
- Improved sdkVersion and appVersion reporting.
- Include confidenceRate and confidenceMsg to show the trust level in pgTM.
- Update the remote configuration URL to include siteID, os, app, and osVersion parameters. This will enable to apply configuration for selective audiences.

# Blue Triangle 2.17.1, June 6 2025
### Bug Fixes
- Fixed Launch Time issue on later configuration
- Changed Traffic Segment name and Page type to "ScreenTracker" for Automatic Screen Tracking payloads
- Sending custom variables along with error payloads
  
# Blue Triangle 2.17.0, April 7 2025
### New Features
- Added support for Blue Triangle and Microsoft Clarity session mapping. Added ability to detect Clarity SDK present in host app, if present associate clarity session url with Timers.

# Blue Triangle 2.16.2, Feb 3 2025
### New Features
- Ability to remotely disable SDK. SDK now has a remote configuration field that can enable/disable SDK on the fly without needing any code push. Once the SDK receives the setting from portal as disabled, the SDK will turn off all tracking and reporting.

# Blue Triangle 2.16.1, Dec 27 2024
### New Features
- Ability to remotely ignore automatically tracked screen names. Developers can configure a list of page names from the BlueTriangle portal, which will be ignored from tracking. Any Activity/Fragment class name or page name given in Compose `BttTimerEffect(_)` side-effect will also be ignored. These names are case-sensitive. This feature allows developers to remotely calibrate the list of Activities/Fragments or Composables they want to track at any time.

# Blue Triangle 2.16.0, Dec 6 2024
### New Features
- Ability to remotely override Network Sample Rate
- Improved method for testing SDK integration using system properties via adb shell, for testing full Network Sample Rate within debug environment

# Blue Triangle 2.15.0, Oct 23 2024
### New Features
- Added support for Custom Variables

# Blue Triangle 2.14.0, Oct 8 2024
### Feature Improvements
- Adding support for collecting Cellular Network Type

# Blue Triangle 2.13.1, Sept 20 2024
### Feature Improvements
- Adding support for collecting Android Device Model

# Blue Triangle 2.13.0, Sept 4 2024
### Feature Improvements
- Added session expiry after 30 minutes of inactivity
- Session will now be maintained within 30 minutes duration across app background, app kills and system reboots
- Automatically updates session in WebView on session expiry

# Blue Triangle 2.12.2, Aug 5 2024
### Bug Fixes
- Hot fix for FragmentLifecycleTracker crash

# Blue Triangle 2.12.1, Jul 24 2024
### Bug Fixes
- Hot Fix for ConcurrentModificationException and NullPointerException

# Blue Triangle 2.12.0, Jun 17 2024
### New Features
- Implemented Warm Launch
- Added Cart Count and Cart Count Checkout Revenue fields to Timer
- Automatically taking Order Time to be Timer's end time

# Blue Triangle 2.11.1, Jun 10 2024
### Bug Fixes
- Fixed crash in NetworkTimelineTracker

# Blue Triangle 2.11.0, May 21 2024
### New Features
- Automatic Hot and Cold Launch Time Tracking
### Bug Fixes
- Fixed cases where Network State was not tracked with client side Network Errors
- Fixed crash in Network State implementation for Android Versions 11 and below

# Blue Triangle 2.10.0, May 2 2024
### Feature Improvements
- SDK can now be configured with only the Site ID, with all stat tracking enabled by default
### Minor Bug Fixes
- Fixed bug related to debug logging

# Blue Triangle 2.9.0, Mar 18 2024
### New Features
- Network state capture
- WebView tracking
- Memory Warning
### Feature Improvements
- Optimized CPU and Memory Tracking
- Improved offline caching mechanism with the inclusion of Memory limit and Expiration.
- Added support for capturing Network Errors

# Blue Triangle 2.8.1, Sept 21 2023
### New Features
- App Launch Time tracking
### Bug Fixes
- Fixed edge case where Screen Tracking performance time reported incorrectly for Composable.
- Fixed page name reported for ANR Warnings.

# Blue Triangle 2.8.0, Jul 14 2023
### New Features
- Automated Screen View Tracking activities, fragments, and composables
- Application Not Responding tracking and reporting as 'ANRWarnings'
### Bug Fixes
- All crashes and ANRWarnings now correctly report the screen where the error occurs
