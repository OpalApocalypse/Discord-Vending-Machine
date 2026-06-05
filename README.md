# Discord Vending Machine

A Discord bot that turns server memes, quotes, links, images, and inside jokes into a little vending machine ritual.

Post the machine, pick a slot like `A1`, and the bot makes a small show of dispensing the result into a configured dropbox channel.

```text
+-------------------------------+
|       DISCORD VENDING         |
+---------+---------+-----------+
| A1      | A2      | A3        |
| Cats    | Dogs    | Otters    |
+---------+---------+-----------+
| B1      | B2      | B3        |
| Memes   | Wholeso | Cringe    |
+---------+---------+-----------+
| C1      | C2      | C3        |
| Quotes  | Waifu   | Frank     |
+---------+---------+-----------+
```

## What it does

- Posts an embedded vending machine display with slot buttons laid out like the machine grid.
- Supports `/vend code` for modern Discord slash-command use.
- Shows the coin/gears/clunk sequence as one private interaction message that edits itself.
- Dispenses the final item into a configured output channel.
- Updates the clicked vending machine message with the latest drop-box slot inside the display.
- Stores editable stock/settings in `data/machine.json`.
- Includes admin stock commands for adding, clearing, enabling, disabling, and reloading slots.

## Requirements

- Java 21
- Maven 3.9+, or the included `mvnw.cmd` wrapper on Windows
- A Discord bot token

## Setup

1. Copy `.env.example` to `.env`.
2. Set `DISCORD_TOKEN`.
3. For faster slash-command testing, set `GUILD_ID` to your test server ID. Leave it blank for global commands.
4. Invite the bot with the `bot` and `applications.commands` scopes.
5. Run on Windows PowerShell:

```powershell
.\mvnw.cmd package
java -jar .\target\discord-vending-machine-1.0.0-SNAPSHOT.jar
```

If you already installed Maven, `mvn package` works too. On first launch, the bot creates `data/machine.json` with sample stock.

## Commands

- `/machine` posts the current vending machine.
- `/vend code:A1` dispenses from a slot.
- `/stock list` shows slots, item counts, and dispense counts.
- `/stock add code:A1 label:Cats content:...` adds an item to a slot.
- `/stock clear code:A1` empties a slot.
- `/stock enable code:A1` and `/stock disable code:A1` toggle availability.
- `/stock set-output channel-id:...` sets the dropbox channel.
- `/stock reload` reloads `data/machine.json` after manual edits.

Stock commands require Manage Server, Administrator, or `OWNER_ID` in `.env`.

For the most vending-machine-like flow, create two channels:

- a vending-machine channel where you post `/machine` and users press slot buttons
- a drop-box channel configured with `/stock set-output`, where the actual result appears after a short delay

The coin/gears/clunk sequence is shown privately to the user who pressed the button or ran `/vend`. The final reveal still lands in the drop-box channel. The machine message then edits its drop-box display to show the latest delivered slot.

## Stock format

The runtime stock file is JSON:

```json
{
  "title": "VENDING MACHINE",
  "outputChannelId": "",
  "dispenseSequence": [
    "`coin inserted for {code}...`",
    "`gears turning...`",
    "`clunk.`"
  ],
  "slots": {
    "A1": {
      "code": "A1",
      "label": "Cats",
      "enabled": true,
      "dispensed": 0,
      "items": [
        {
          "content": "A tiny cat fact escaped the vending coil.",
          "imageUrl": "",
          "rare": false,
          "weight": 1
        }
      ]
    }
  }
}
```

Set `imageUrl` for image embeds. Increase `weight` to make an item appear more often. Mark `rare` to give the drop a special color/footer.

## Notes

The original prototype broke because it depended on an old Java Discord API setup and only committed compiled output. This version checks in source code, uses current JDA, includes a Windows Maven wrapper, avoids Message Content Intent, and has CI so dependency breaks are visible sooner.
