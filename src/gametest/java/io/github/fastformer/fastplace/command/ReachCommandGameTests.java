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
      for (String command : new String[]{"reach close 5", "reach far 20", "reach 5 20", "reach reset"}) {
         var parsed = dispatcher.parse(command, null);
         helper.assertTrue(!parsed.getReader().canRead(), command);
         helper.assertTrue(parsed.getContext().getCommand() != null, command);
      }
      for (String command : new String[]{"reach", "reach 5", "reach set 5", "reach block set 5",
         "reach entity reset", "reach close 2.5", "reach far -1"}) {
         var parsed = dispatcher.parse(command, null);
         helper.assertTrue(parsed.getReader().canRead() || parsed.getContext().getCommand() == null, command);
      }
      helper.succeed();
   }
}
