# Debloating a TCL Google TV (optional)

Not needed for the dashboard. Tested on one TCL model running Android 14; package names vary,
so list yours first and save the output:

    adb shell pm list packages -s > system-packages.txt
    adb shell pm list packages -3 > user-packages.txt

Remove for the current user only, which can be undone:

    adb shell pm uninstall -k --user 0 <package>
    adb shell cmd package install-existing <package>     # undo

| Package | What it is |
| --- | --- |
| `tv.samba.ssm.ui` | Samba TV viewing tracking |
| `com.tcl.channelplus` | TCL Channel |
| `com.tcl.messagebox` | TCL messages and promotions |
| `com.tcl.interactive` | TCL interactive ads |
| `com.tcl.esticker`, `com.tcl.exhibit` | Shop display mode |
| `com.tcl.logkit` | Log uploader |
| `com.tcl.usercenter` | TCL account |
| `com.tcl.ocean.instructions`, `com.tcl.repairguide` | Manual and repair guide |
| `com.tcl.waterfall.overseas` | TCL's own home screen |
| `com.tcl.smartalexa`, `com.tcl.browser` | Alexa and browser, if unused |

Preinstalled streaming apps can be removed the same way if you don't use them.

**Leave alone:** Google services, setup and system update packages, keyboards, and anything
handling TV inputs.
