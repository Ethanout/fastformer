# FastFormer

FastFormer is a NeoForge 1.21.1 creative-mode building assistant.

## Current prototype

This branch uses a hybrid input model: the server owns sessions/settings/actions, and a tiny optional client helper only reports exact Sprint-key presses. There are no mixins and no new key mappings.

- Only operates for creative-mode players.
- `/fastformer toggle` enables or disables the feature for the executing player.
- `/fastformer cancel` cancels the active session.
- `/fastformer mode` cycles the current stage mode as a fallback for clients without the helper.
- `/fastformer status` prints the stored server-side setting.
- Right click with a block item starts or continues a Fast Place session.
- A session starts from `RightClickItem` when the server-side extended raycast hits a block beyond normal interaction reach.
- Right clicking a reachable block while a session is active adds that placement point.
- Left clicking a block while a session is active undoes the last point.
- Q is treated as quit through `ItemTossEvent`: the toss is cancelled, the removed stack is restored, and the session is cancelled.
- F is treated as fill through `LivingSwapItemsEvent.Hands`: while a session is active, it cycles fill mode and cancels the hand swap.
- If the main hand is empty and the offhand has an item, F is allowed to behave like vanilla so the offhand item can move to the main hand.
- Pressing the vanilla Sprint key while a session is active cycles the current stage mode when the client helper is installed.
- Clients with the helper receive an optional preview-state payload and render selected points, the cursor candidate, and the current wire outline in-world.
- Stage modes and fill mode are stored in the player's persistent NeoForge data.
- Sessions store a variable `List<BlockPos>` instead of fixed `A/B/C/D` fields.

## Compatibility notes

- The Sprint-key payload is registered as optional. Clients without the mod can still connect and use server-side actions, but they need `/fastformer mode` for stage-mode cycling.
- The client checks negotiated payload channels before sending, so pressing Sprint on a server without FastFormer will not send an unknown custom payload.
- The client helper listens to the configured Sprint key mapping, so remapping Sprint still works and double-tapping W does not cycle FastFormer mode.
- Outline preview is visual only. The server session remains authoritative, and clients without the helper simply do not render the preview.
- A pure server mod cannot observe left click in empty air. Left-click undo works when the client sends a block attack packet.
- `ItemTossEvent` also covers inventory drag-dropping stacks outside screens. If a player does that while a Fast Place session is active, FastFormer will treat it like Q/quit.
- Custom translation keys are not used for runtime messages because clients may not have the mod installed.
