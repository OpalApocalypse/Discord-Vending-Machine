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

- Posts an embedded vending machine display with clickable slot buttons.
- Supports `/vend code` for modern Discord slash-command use.
- Supports the original concept: typing `A1` or `!A1` in a configured input channel.
- Dispenses into a configured output channel with a tiny anticipation sequence.
- Updates the clicked vending machine message with a latest drop-box link after the drop lands.
- Tries to delete typed input commands so the input channel can feel like a keypad.
- Stores editable stock/settings in `data/machine.json`.
- Includes admin stock commands for adding, clearing, enabling, disabling, and reloading slots.

## Requirements

- Java 21
- Maven 3.9+
- A Discord bot token
- Message Content Intent enabled in the Discord developer portal if you want legacy typed-code input.

## Setup

1. Copy `.env.example` to `.env`.
2. Set `DISCORD_TOKEN`.
3. For faster slash-command testing, set `GUILD_ID` to your test server ID. Leave it blank for global commands.
4. Invite the bot with the `bot` and `applications.commands` scopes.
5. Run:

```bash
mvn package
java -jar target/discord-vending-machine-1.0.0-SNAPSHOT.jar
```

On first launch, the bot creates `data/machine.json` with sample stock.

## Commands

- `/machine` posts the current vending machine.
- `/vend code:A1` dispenses from a slot.
- `/stock list` shows slots, item counts, and dispense counts.
- `/stock add code:A1 label:Cats content:...` adds an item to a slot.
- `/stock clear code:A1` empties a slot.
- `/stock enable code:A1` and `/stock disable code:A1` toggle availability.
- `/stock set-input channel-id:...` sets the channel where typed codes are accepted.
- `/stock set-output channel-id:...` sets the dropbox channel.
- `/stock reload` reloads `data/machine.json` after manual edits.

Stock commands require Manage Server, Administrator, or `OWNER_ID` in `.env`.

For the most vending-machine-like flow, create two channels:

- an input/keypad channel where people type `!A1`, or where you pin/post `/machine`
- a drop-box channel configured with `/stock set-output`, where the actual result appears after a short delay

If someone presses a button on the machine message, the result still lands in the drop-box channel. The machine message then edits itself with a link to that drop so people can choose when to peek.

## Stock format

The runtime stock file is JSON:

```json
{
  "title": "VENDING MACHINE",
  "inputChannelId": "",
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

The original prototype broke because it depended on an old Java Discord API setup and only committed compiled output. This version checks in source code, uses current JDA, and has CI so dependency breaks are visible sooner.
