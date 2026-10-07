package io.github.fastformer.fastplace.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import io.github.fastformer.FastFormer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public class ReachCommandGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "reach_raycast")
   public static void acceptsOnlyTheNewIntegerForms(GameTestHelper helper) {
      var dispatcher = new CommandDispatcher<CommandSourceStack>();
      dispatcher.register(ReachCommand.create());
      for (String command : new String[]{"reach", "reach 20", "reach 0", "reach -1"}) {
         var parsed = dispatcher.parse(command, null);
         helper.assertTrue(!parsed.getReader().canRead(), command);
         helper.assertTrue(parsed.getContext().getCommand() != null, command);
      }
      for (String command : new String[]{"reach reset", "reach close 5", "reach 5 20", "reach 2.5",
         "tinker reach", "tinker reach 0", "tinker reach 129",
         "tinker reach close 5", "tinker reach 2.5", "tinker reach -1"}) {
         var parsed = dispatcher.parse(command, null);
         helper.assertTrue(parsed.getReader().canRead() || parsed.getContext().getCommand() == null, command);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "reach_raycast")
   public static void blockReachUsesTheVanillaAttributeAndSurvivesPlayerReplacement(GameTestHelper helper)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
      var player = helper.makeMockServerPlayerInLevel();
      var reach = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
      var entity = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
      double originalEntity = entity.getBaseValue();
      io.github.fastformer.fastplace.settings.PlayerBlockReach.initialize(player);
      helper.assertTrue(reach.getBaseValue() == 20, "default block range was not 20");
      var dispatcher = new CommandDispatcher<CommandSourceStack>();
      dispatcher.register(ReachCommand.create());
      var source = player.createCommandSourceStack();
      dispatcher.execute("reach 7", source);
      helper.assertTrue(reach.getBaseValue() == 7, "reach command did not write the vanilla base attribute");
      dispatcher.execute("reach", source);
      helper.assertTrue(reach.getBaseValue() == 7, "query changed the range");
      io.github.fastformer.fastplace.settings.PlayerBlockReach.initialize(player);
      helper.assertTrue(reach.getBaseValue() == 7, "login initialization overwrote a configured range");
      reach.setBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue());
      io.github.fastformer.fastplace.settings.PlayerBlockReach.initialize(player);
      helper.assertTrue(reach.getBaseValue() != 20, "login initialization overwrote an intentional vanilla attribute value");
      dispatcher.execute("reach 9", source);
      var replacement = helper.makeMockServerPlayerInLevel();
      io.github.fastformer.fastplace.settings.PlayerBlockReach.copy(player, replacement);
      helper.assertTrue(replacement.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).getBaseValue() == 9,
         "player replacement lost the configured range");
      helper.assertTrue(entity.getBaseValue() == originalEntity, "block reach changed entity reach");
      helper.succeed();
   }
}
