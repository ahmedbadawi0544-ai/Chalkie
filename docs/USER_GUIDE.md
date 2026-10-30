# Chalkie user guide

## Set up a team (admin)
1. Install Chalkie and open it. Enter **your name**.
2. Under *Create a new team*:
   - **Team name**: required.
   - **Extra channels**: optional, comma separated, e.g. `Splicing, Civil works`. Channel 0 is always *General*.
   - **Site Wi-Fi name / password**: optional. Phones that join get this network offered automatically.
3. Tap **Create team**. You are the team's first **supervisor**.

## Invite people
Tap the **QR** icon at the top of the main screen.
- **Team member**: for everyone.
- **Supervisor**: only for people who should be able to talk over others. This option appears only on supervisor phones.

The QR code contains the team key. Anyone who scans it can join and listen, so show it only to your team.

## Join a team
Enter your name, tap **Scan group QR**, and point the camera at the code. Then allow:
- **Microphone**: to talk.
- **Nearby devices / Bluetooth**: to find teammates without internet.
- **Notifications**: to keep Chalkie running in the background.
- **Battery**: tap *Allow* on the banner so Chalkie keeps working with the screen off.

## The status bar
| Chip | Meaning |
|---|---|
| **BLE n** | Bluetooth links to n nearby phones. Grey with "bluetooth off" means you are unreachable. |
| **WD hub · n / WD leaf / WD joining…** | Wi-Fi Direct star: this phone is the hub (with n phones), or connected to the hub. |
| **LAN · n** | Connected to n teammates over the building Wi-Fi. |
| **LAN unicast** | Wi-Fi works, but the access point blocks multicast. Still fine. |
| **LAN checking… / standby** | Looking for teammates on this Wi-Fi, or none found. |
| **LAN blocked** | The access point stops phones talking to each other (guest network). Use another network. |
| **Bridge** | This phone relays between Wi-Fi users and phones that are not on Wi-Fi. |

The small line underneath shows packets sent (↑) and received (↓) per radio, and how many voice messages are still waiting to be delivered.

## Talk (Talk tab)
- **Hold** the big button to talk and release to stop. You hear a chirp when you may talk, and a busy tone if someone else is talking.
- The button turns **green with a name** while a teammate talks.
- Talks stop automatically after 60 s.
- **Channel**: pick one from the list. You always also hear *General*.
- **Quick texts**: tap one to send it to your channel.

## Teammates (Team tab)
Each row shows a teammate's links, cheapest first, with ★ on the one in use: for example `LAN ★ + BLE -45 dBm`. Teammates you reach only through others show "via n relays". Tap a teammate to:
- **Hold to call privately**: only they hear you, even if the channel is busy.
- **Hold to record a voice message**: release to send. If they are out of reach the message waits on your phone and is delivered as soon as they are back. You'll see "delivered ✓" in Messages.
- **Quick text**: sends to that teammate only.

## Messages tab
Shows voice messages (▶ to play, 🗑 to delete; an amber dot means not heard yet) and the texts of this session.

## Map tab
Shows the live network: you in the middle, direct neighbours around you, and others further out.
- **Link colours**: blue LAN, green Wi-Fi Direct, amber Bluetooth (labelled with signal strength). Dashed lines are links between other phones.
- **Badges**: **H** hub, **B** bridge, **S** supervisor.

## Log tab
Shows every talk (who, channel, duration), private call, text, voice message, role change and network event. **Export CSV** saves the log as a spreadsheet file; **Clear** empties it.

## Settings (gear icon)
- **PTT key**: choose **Volume −**, **Learn key** (then press any key, such as the XCover/Active side key), or **Off**. It works while Chalkie is on screen.
- **Rugged-phone PTT button**: listens for the PTT key of rugged phones, also with the screen off. It is off by default; enable it only on dedicated work phones.
- **Opus voice codec**: uses much less radio time. Turn it off if voice sounds broken.
- **Headset button**: short press to start or stop talking, or hold it to talk.
- **Quick texts**: one per line, then *Save*.
- **Go offline / Leave team**.

## Tips
- Keep Bluetooth on: it is the fallback that always works.
- On Android 11 and older, Location must be on for Bluetooth scanning.
- After an update, open Chalkie once so it can use the microphone again.
