# Backend relay and Android Recents

Backend mode keeps its CarPlay controller owner in the existing `.carplay` task.
After a successful return to DiPlay home, that host task is excluded from Recents.
The home/status activity is opened in the launcher task using `NEW_TASK`,
`CLEAR_TOP`, and `SINGLE_TOP`; repeated returns reuse its root. The retained host
is not finished or removed. Native projection retains its existing navigation
and Recents behavior. This is a task-list cleanup, not a power-saving change.

A newly created host first restores its Recents visibility so native projection
and unfinished setup/permission screens remain reachable. Its exclusion happens
only after home can be opened. Backend notification taps open home directly;
native notification taps still open projection. Missing or rejected OEM AppTask
operations are logged without stopping forwarding; those devices may still show
an extra card.

Back on the backend home backgrounds its task while a session exists. Swiping
that home card away does not intentionally stop the relay: the existing backend
foreground-service policy retains the connection. Use Disconnect to stop it.
Android/OEM process reclamation and force-stop can still terminate the session.
An upgrade's old home instance inside the host task is not destructively cleared.

## Validation

`CarPlayTaskNavigationTest` exercises production intent construction and task
selection on API 28 and 33: backend and native routes, notification destinations,
show-before-hide ordering, visibility reset, absent tasks, launch failures and
OEM task API failures. Run the Android checks workflow for unit tests, lint and
builds. These unit tests do not simulate Android's real task manager.

On an Android device (including the target OEM), verify:

1. Cold backend start from the launcher and from USB attachment. Complete or
   cancel each VPN/wireless/location permission prompt; pending setup remains
   reachable and the home/status screen can be opened.
2. Connect a browser and verify forwarding, touch and reconnects. Once home is
   shown, Recents has one DiPlay card. Repeat Connect/return and notification taps;
   no extra home instances/cards should appear.
3. Test a browser-approval prompt after returning home, reject it, then retry and
   approve it. Existing approval behavior must remain intact.
4. Back from settings returns home; Back from home backgrounds DiPlay. Reopen
   from Recents, launcher and notification. Forwarding stays active.
5. Swipe the home card away during forwarding. Forwarding continues under the
   existing backend service policy. Reopen from the launcher or notification;
   the home/status task returns without a duplicate. Disconnect still stops it.
6. Switch backend → native → backend using the supported disconnect/reconnect
   flow. Native projection is visible in Recents and its Back/notification,
   navigation, cluster and permission behavior is unchanged.
7. Upgrade while the old backend host task contains an older home instance.
   Reenter and return home; only the launcher card remains, with no active-session
   teardown. Repeat with no existing launcher task and after process recreation.
