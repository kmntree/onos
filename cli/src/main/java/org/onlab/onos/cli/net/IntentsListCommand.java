package org.onlab.onos.cli.net;

import org.apache.karaf.shell.commands.Command;
import org.onlab.onos.cli.AbstractShellCommand;
import org.onlab.onos.net.intent.Intent;
import org.onlab.onos.net.intent.IntentService;
import org.onlab.onos.net.intent.IntentState;

/**
 * Lists the inventory of intents and their states.
 */
@Command(scope = "onos", name = "intents",
         description = "Lists the inventory of intents and their states")
public class IntentsListCommand extends AbstractShellCommand {

    @Override
    protected void execute() {
        IntentService service = get(IntentService.class);
        for (Intent intent : service.getIntents()) {
            IntentState state = service.getIntentState(intent.id());
            print("%s %s %s", intent.id(), state, intent);
        }
    }

}