package de.qtc.rmg;

import de.qtc.rmg.internal.ArgumentHandler;
import de.qtc.rmg.operations.Dispatcher;
import de.qtc.rmg.operations.Operation;
import de.qtc.rmg.utils.RMGUtils;

/**
 * The Starter class contains the entrypoint of remote-method-guesser. It is responsible
 * for creating a Dispatcher object, that is used to dispatch the actual method call.
 *
 * @author Tobias Neitzel (@qtc_de)
 */
public class Starter
{
    public static void main(String[] argv)
    {
        ArgumentHandler handler = new ArgumentHandler(argv);
        Operation operation = handler.getAction();

        RMGUtils.init();
        RMGUtils.disableWarning();
        RMGUtils.enableCodebaseCollector();
        Dispatcher dispatcher = new Dispatcher(handler);

        operation.invoke(dispatcher);
    }
}
