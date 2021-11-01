package de.qtc.rmg.internal;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import de.qtc.rmg.annotations.Parameters;
import de.qtc.rmg.io.Logger;
import de.qtc.rmg.operations.Operation;
import de.qtc.rmg.operations.PortScanner;
import de.qtc.rmg.operations.ScanAction;
import de.qtc.rmg.plugin.PluginSystem;
import de.qtc.rmg.utils.RMGUtils;
import de.qtc.rmg.utils.Security;
import de.qtc.rmg.utils.YsoIntegration;

/**
 * This is a helper class that handles all the argument parsing related stuff
 * during an execution of rmg. In future we may move to an module based
 * argument parser, as the amount of options and actions has become quite
 * high. However, for now the current parsing should be sufficient.
 *
 * To implement more complicated parsing logic, it is recommended to look at the
 * de.qtc.rmg.annotations.Parameters class.
 *
 * @author Tobias Neitzel (@qtc_de)
 */
public class ArgumentParser {

    private Options options;
    private String helpString;
    private HelpFormatter formatter;
    private CommandLineParser parser;
    private CommandLine cmdLine;
    private List<String> argList;
    private Properties config;

    private String regMethod = null;
    private String dgcMethod = null;
    private Operation action = null;
    private RMIComponent component = null;

    private  String defaultConfiguration = "/config.properties";

    /**
     * Creates the actual parser object and initializes it with some default options
     * and parses the current command line. Parsed parameters are stored within the
     * parameters HashMap.
     */
    public ArgumentParser(String[] argv)
    {
        this.parser = new DefaultParser();
        this.options = getParserOptions();
        this.helpString = getHelpString();
        this.formatter = new HelpFormatter();
        this.formatter.setWidth(130);
        this.formatter.setDescPadding(6);

        this.parse(argv);
    }

    /**
     * Parses the specified command line arguments and handles some shortcuts.
     * E.g. the --help and --trusted options are already caught at this level and
     * set the corresponding global variables in other classes.
     *
     * @param argv arguments specified on the command line
     */
    private void parse(String[] argv)
    {
        try {
            cmdLine = parser.parse(this.options, argv);
        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage() + "\n");
            printHelpAndExit(1);
        }

        this.config = new Properties();
        loadConfig(cmdLine.getOptionValue(RMGOption.CONFIG.name, null));

        if( cmdLine.hasOption(RMGOption.HELP.name) ) {
            printHelpAndExit(0);
        }

        if( cmdLine.hasOption(RMGOption.TRUSTED.name) )
            Security.trusted();

        if( cmdLine.hasOption(RMGOption.NO_COLOR.name) )
            Logger.disableColor();

        if( cmdLine.hasOption(RMGOption.VERBOSE.name) )
            Logger.verbose = true;

        PluginSystem.init(cmdLine.getOptionValue("plugin", null));
        ExceptionHandler.showStackTrace(cmdLine.hasOption("stack-trace"));
        YsoIntegration.setYsoPath(cmdLine.getOptionValue("yso", config.getProperty("ysoserial-path")));

        preapreParameters();
    }

    /**
     * Loads the remote-method-guesser configuration file from the specified destination. The default configuration
     * is always loaded. If the filename parameter is not null, an additional user specified config is loaded, that
     * may overwrites some configurations.
     *
     * @param filename file system path to load the configuration file from
     */
    private void loadConfig(String filename)
    {
        try {
            InputStream configStream = null;

            configStream = ArgumentParser.class.getResourceAsStream(defaultConfiguration);
            config.load(configStream);
            configStream.close();

            if( filename != null ) {
                configStream = new FileInputStream(filename);
                config.load(configStream);
                configStream.close();
            }

        } catch( IOException e ) {
            ExceptionHandler.unexpectedException(e, "loading", ".properties file", true);
        }
    }


    /**
     * Command line parameters are stored within the RMGOption enum. This function parses the values
     * from the command line and sets the corresponding enum values.
     */
    private void preapreParameters()
    {
        RMGOption.SAMPLE_FOLDER.setValue(cmdLine, config.getProperty("sample-folder"));
        RMGOption.WORDLIST_FILE.setValue(cmdLine, config.getProperty("wordlist-file"));
        RMGOption.TEMPLATE_FOLDER.setValue(cmdLine, config.getProperty("template-folder"));
        RMGOption.WORDLIST_FOLDER.setValue(cmdLine, config.getProperty("wordlist-folder"));
        RMGOption.SIGNATURE.setValue(cmdLine);
        RMGOption.BOUND_NAME.setValue(cmdLine);
        RMGOption.SSRFResponse.setValue(cmdLine);
        RMGOption.OBJID.setValue(cmdLine, null);
        RMGOption.COMPONENT.setValue(cmdLine);

        RMGOption.NO_PROGRESS.setBoolean(cmdLine);
        RMGOption.NO_CANARY.setBoolean(cmdLine);
        RMGOption.SSL.setBoolean(cmdLine);
        RMGOption.SSRF.setBoolean(cmdLine);
        RMGOption.FOLLOW.setBoolean(cmdLine);
        RMGOption.UPDATE.setBoolean(cmdLine);
        RMGOption.CREATE_SAMPLES.setBoolean(cmdLine);
        RMGOption.ZERO_ARG.setBoolean(cmdLine);
        RMGOption.LOCALHOST_BYPASS.setBoolean(cmdLine);
        RMGOption.FORCE_GUESSING.setBoolean(cmdLine);
        RMGOption.GUESS_DUPLICATES.setBoolean(cmdLine);
        RMGOption.GOPHER.setBoolean(cmdLine);

        try {
            RMGOption.ARGUMENT_POS.setInt(cmdLine, -1);
            RMGOption.THREADS.setInt(cmdLine, Integer.valueOf(config.getProperty("threads")));

        } catch(ParseException e) {
            Logger.printlnPlainMixedYellow("Error: Invalid parameter type for argument", "ARGUMENT_POS | THREADS");
            System.out.println("");
            printHelpAndExit(1);
        }

        getAction();
        setTarget();
        validateOperation(action);
    }

    /**
     * The RMGOption Target is a helper option that is used for argument validation on actions
     * that require a target. In these cases, users can specify either a bound name, an ObjID
     * or a default Java RMI component. This function sets the RMGOption.Target's value accordingly.
     */
    private void setTarget()
    {

        boolean objID = RMGOption.OBJID.notNull();
        boolean bound = RMGOption.BOUND_NAME.notNull();
        boolean comp = RMGOption.COMPONENT.notNull();

        if( objID ? (bound || comp) : (bound && comp) ) {

            if( action != Operation.BIND && action != Operation.REBIND ) {
                Logger.eprintMixedYellow("Only one of", "--objid, --bound-name", "or ");
                Logger.printlnPlainMixedYellowFirst("--component", "can be specified.");
                RMGUtils.exit();
            }
        }

        if( objID )
            RMGOption.TARGET.setValue(RMGOption.OBJID.value);

        else if( bound )
            RMGOption.TARGET.setValue(RMGOption.BOUND_NAME.value);

        else if( comp )
            RMGOption.TARGET.setValue(RMGOption.COMPONENT.value);
    }

    /**
     * Returns the number of specified positional arguments.
     *
     * @return number of positional arguments.
     */
    public int getArgumentCount()
    {
        if( this.argList != null ) {
            return this.argList.size();

        } else {
            this.argList = cmdLine.getArgList();
            return this.argList.size();
        }
    }

    /**
     * This function constructs all required parser options. rmg uses long options
     * only and does not define short versions for any option.
     *
     * @return parser options.
     */
    private Options getParserOptions()
    {
        Options options = new Options();

        Option position = new Option(null, RMGOption.ARGUMENT_POS.name, RMGOption.ARGUMENT_POS.requiresValue, RMGOption.ARGUMENT_POS.description);
        position.setArgName("int");
        position.setRequired(false);
        position.setType(Number.class);
        options.addOption(position);

        Option name = new Option(null, RMGOption.BOUND_NAME.name, RMGOption.BOUND_NAME.requiresValue, RMGOption.BOUND_NAME.description);
        name.setArgName("name");
        name.setRequired(false);
        options.addOption(name);

        Option canary = new Option(null, RMGOption.NO_CANARY.name, RMGOption.NO_CANARY.requiresValue, RMGOption.NO_CANARY.description);
        canary.setRequired(false);
        options.addOption(canary);

        Option component = new Option(null, RMGOption.COMPONENT.name, RMGOption.COMPONENT.requiresValue, RMGOption.COMPONENT.description);
        component.setArgName("component");
        component.setRequired(false);
        options.addOption(component);

        Option configOption = new Option(null, RMGOption.CONFIG.name, RMGOption.CONFIG.requiresValue, RMGOption.CONFIG.description);
        configOption.setArgName("file");
        configOption.setRequired(false);
        options.addOption(configOption);

        Option samples = new Option(null, RMGOption.CREATE_SAMPLES.name, RMGOption.CREATE_SAMPLES.requiresValue, RMGOption.CREATE_SAMPLES.description);
        samples.setRequired(false);
        options.addOption(samples);

        Option follow = new Option(null, RMGOption.FOLLOW.name, RMGOption.FOLLOW.requiresValue, RMGOption.FOLLOW.description);
        follow.setRequired(false);
        options.addOption(follow);

        Option forceGuessing = new Option(null, RMGOption.FORCE_GUESSING.name, RMGOption.FORCE_GUESSING.requiresValue, RMGOption.FORCE_GUESSING.description);
        forceGuessing.setRequired(false);
        options.addOption(forceGuessing);

        Option gopher = new Option(null, RMGOption.GOPHER.name, RMGOption.GOPHER.requiresValue, RMGOption.GOPHER.description);
        gopher.setRequired(false);
        options.addOption(gopher);

        Option guessDuplicates = new Option(null, RMGOption.GUESS_DUPLICATES.name, RMGOption.GUESS_DUPLICATES.requiresValue, RMGOption.GUESS_DUPLICATES.description);
        guessDuplicates.setRequired(false);
        options.addOption(guessDuplicates);

        Option help = new Option(null, RMGOption.HELP.name, RMGOption.HELP.requiresValue, RMGOption.HELP.description);
        help.setRequired(false);
        options.addOption(help);

        Option local = new Option(null, RMGOption.LOCALHOST_BYPASS.name, RMGOption.LOCALHOST_BYPASS.requiresValue, RMGOption.LOCALHOST_BYPASS.description);
        local.setRequired(false);
        options.addOption(local);

        Option noColor = new Option(null, RMGOption.NO_COLOR.name, RMGOption.NO_COLOR.requiresValue, RMGOption.NO_COLOR.description);
        noColor.setRequired(false);
        options.addOption(noColor);

        Option noProgress = new Option(null, RMGOption.NO_PROGRESS.name, RMGOption.NO_PROGRESS.requiresValue, RMGOption.NO_PROGRESS.description);
        noProgress.setRequired(false);
        options.addOption(noProgress);

        Option objID = new Option(null, RMGOption.OBJID.name, RMGOption.OBJID.requiresValue, RMGOption.OBJID.description);
        objID.setRequired(false);
        objID.setArgName("objID");
        options.addOption(objID);

        Option plugin = new Option(null, RMGOption.PLUGIN.name, RMGOption.PLUGIN.requiresValue, RMGOption.PLUGIN.description);
        plugin.setArgName("path");
        plugin.setRequired(false);
        options.addOption(plugin);

        Option outputs = new Option(null, RMGOption.SAMPLE_FOLDER.name, RMGOption.SAMPLE_FOLDER.requiresValue, RMGOption.SAMPLE_FOLDER.description);
        outputs.setArgName("folder");
        outputs.setRequired(false);
        options.addOption(outputs);

        Option signature = new Option(null, RMGOption.SIGNATURE.name, RMGOption.SIGNATURE.requiresValue, RMGOption.SIGNATURE.description);
        signature.setArgName("method");
        signature.setRequired(false);
        options.addOption(signature);

        Option ssl = new Option(null, RMGOption.SSL.name, RMGOption.SSL.requiresValue, RMGOption.SSL.description);
        ssl.setRequired(false);
        options.addOption(ssl);

        Option ssrf = new Option(null, RMGOption.SSRF.name, RMGOption.SSRF.requiresValue, RMGOption.SSRF.description);
        ssrf.setRequired(false);
        options.addOption(ssrf);

        Option ssrfResponse = new Option(null, RMGOption.SSRFResponse.name, RMGOption.SSRFResponse.requiresValue, RMGOption.SSRFResponse.description);
        ssrfResponse.setRequired(false);
        options.addOption(ssrfResponse);

        Option stackTrace = new Option(null, RMGOption.STACK_TRACE.name, RMGOption.STACK_TRACE.requiresValue, RMGOption.STACK_TRACE.description);
        stackTrace.setRequired(false);
        options.addOption(stackTrace);

        Option templates = new Option(null, RMGOption.TEMPLATE_FOLDER.name, RMGOption.TEMPLATE_FOLDER.requiresValue, RMGOption.TEMPLATE_FOLDER.description);
        templates.setArgName("folder");
        templates.setRequired(false);
        options.addOption(templates);

        Option threads = new Option(null, RMGOption.THREADS.name, RMGOption.THREADS.requiresValue, RMGOption.THREADS.description);
        threads.setArgName("int");
        threads.setRequired(false);
        threads.setType(Number.class);
        options.addOption(threads);

        Option trusted = new Option(null, RMGOption.TRUSTED.name, RMGOption.TRUSTED.requiresValue, RMGOption.TRUSTED.description);
        trusted.setRequired(false);
        options.addOption(trusted);

        Option update = new Option(null, RMGOption.UPDATE.name, RMGOption.UPDATE.requiresValue, RMGOption.UPDATE.description);
        update.setRequired(false);
        options.addOption(update);

        Option verbose = new Option(null, RMGOption.VERBOSE.name, RMGOption.VERBOSE.requiresValue, RMGOption.VERBOSE.description);
        verbose.setRequired(false);
        options.addOption(verbose);

        Option wordlist = new Option(null, RMGOption.WORDLIST_FILE.name, RMGOption.WORDLIST_FILE.requiresValue, RMGOption.WORDLIST_FILE.description);
        wordlist.setArgName("file");
        wordlist.setRequired(false);
        options.addOption(wordlist);

        Option wordlistFolder = new Option(null, RMGOption.WORDLIST_FOLDER.name, RMGOption.WORDLIST_FOLDER.requiresValue, RMGOption.WORDLIST_FOLDER.description);
        wordlistFolder.setArgName("folder");
        wordlistFolder.setRequired(false);
        options.addOption(wordlistFolder);

        Option yso = new Option(null, RMGOption.YSO.name, RMGOption.YSO.requiresValue, RMGOption.YSO.description);
        yso.setArgName("file");
        yso.setRequired(false);
        options.addOption(yso);

        Option zeroArg = new Option(null, RMGOption.ZERO_ARG.name, RMGOption.ZERO_ARG.requiresValue, RMGOption.ZERO_ARG.description);
        zeroArg.setRequired(false);
        options.addOption(zeroArg);

        return options;
    }

    /**
     * The validateOperation function is used to validate that all required parameters were specified for an operation.
     * Each operation has one assigned method, that can be annotated with the Parameters annotation. If this annotation
     * is present, this function checks the 'count' and 'requires' attribute and makes sure that the corresponding values
     * have been specified on the command line. Read the de.qtc.rmg.annotations.Parameters class for more details.
     *
     * @param operation Operation to validate
     */
    private void validateOperation(Operation operation)
    {
        Method m = operation.getMethod();
        Parameters paramRequirements = (Parameters)m.getAnnotation(Parameters.class);

        if(paramRequirements == null)
            return;

        this.checkArgumentCount(paramRequirements.count());

        for(RMGOption requiredOption: paramRequirements.requires()) {

            if(requiredOption.value == null) {

                Logger.eprint("Error: The ");
                Logger.printPlainYellow("--" + requiredOption.name);
                Logger.printlnPlainMixedBlue(" option is required for the", operation.toString().toLowerCase(), "operation.");
                RMGUtils.exit();
            }
        }
    }

    /**
     * Returns the help string that is used by rmg. The version information is read from the pom.xml, which makes
     * it easier to keep it in sync. Possible operation names are taken from the Operation enumeration.
     *
     * @return help string.
     */
    private String getHelpString()
    {
        String helpString = "rmg [options] <ip> <port> [<action>]\n\n"
                +"rmg v" + ArgumentParser.class.getPackage().getImplementationVersion()
                +" - Java RMI Vulnerability Scanner\n\n"
                +"Positional Arguments:\n"
                +"    ip                              IP address of the target\n"
                +"    port                            Port of the RMI registry\n"
                +"    action                          One of the possible actions listed below\n\n"
                +"Possible Actions:\n";

        for(Operation op : Operation.values()) {
            helpString += "    "
                    + Logger.padRight(op.toString().toLowerCase() + " " + op.getArgs(), 32)
                    + op.getDescription() + "\n";
        }

        helpString += "\nOptional Arguments:";
        return helpString;
    }

    /**
     * Utility function to show the program help and exit it right away.
     *
     * @param code return code of the program
     */
    private void printHelpAndExit(int code)
    {
        formatter.printHelp(helpString, options);
        System.exit(code);
    }

    /**
     * Takes a number that represents the position of a positional argument and
     * returns the corresponding argument as String. Currently, no error handling
     * is implemented. The checkArgumentCount should therefore be called first.
     *
     * @param position number of the requested positional argument
     * @return String value of the requested positional argument
     */
    public String getPositionalString(int position)
    {
        if( this.argList != null ) {
            return this.argList.get(position);
        } else {
            this.argList = cmdLine.getArgList();
            return this.argList.get(position);
        }
    }

    /**
     * Takes a number that represents the position of a positional argument and
     * returns the corresponding argument as Integer. Currently, no error handling
     * is implemented. The checkArgumentCount should therefore be called first.
     *
     * @param position number of the requested positional argument
     * @return Integer value of the requested positional argument
     */
    public int getPositionalInt(int position)
    {
        try {

            if( this.argList != null ) {
                return Integer.valueOf(this.argList.get(position));
            } else {
                this.argList = cmdLine.getArgList();
                return Integer.valueOf(this.argList.get(position));
            }

        } catch( Exception e ) {
            System.err.println("Error: Unable to parse " + this.argList.get(position) + " as integer.");
            printHelpAndExit(1);
        }
        return 0;
    }


    /**
     * Checks whether the specified amount of positional arguments is sufficiently high.
     * If the number of actual positional arguments is lower than the specified counter,
     * the program exists with an error.
     *
     * @param expectedCount minimum number of arguments
     */
    public void checkArgumentCount(int expectedCount)
    {
         List<String> remainingArgs = cmdLine.getArgList();
         if( remainingArgs.size() < expectedCount ) {
             System.err.println("Error: insufficient number of arguments.\n");
             printHelpAndExit(1);
         }
    }

    /**
     * This function is used to check the requested operation name on the command line and returns the corresponding
     * Operation object. If no operation was specified, the Operation.ENUM is used. Operations are always validated
     * before they are returned. The validation checks if all required parameters for the corresponding operation were
     * specified.
     *
     * @return Operation requested by the client
     */
    public Operation getAction()
    {
        if(this.action != null)
            return this.action;

        else if( this.getArgumentCount() < 3 ) {
            this.action = Operation.ENUM;

        } else {
            this.action = Operation.getByName(this.getPositionalString(2));
        }

        if(this.action == null) {
            Logger.eprintlnMixedYellow("Error: Specified operation", this.getPositionalString(2), "is not supported.");
            printHelpAndExit(1);
        }

        if( action == Operation.SCAN )
            setSocketTimeout();

        return action;
    }

    /**
     * During registry related rmg operations, users can select the registry method
     * that is used for the different RMI calls. This is done by using the --signature
     * option. In contrast to custom remote objects, the method signature is not really
     * parsed. It is only checked for specific keywords and the corresponding registry
     * methods signature is chosen automatically.
     *
     * @return method to use for registry operations - if valid.
     */
    public String getRegMethod()
    {
        if( regMethod != null )
            return regMethod;

        String signature = RMGOption.SIGNATURE.getString();
        String[] supported =  new String[]{"lookup", "unbind", "rebind", "bind"};

        if( signature == null ) {
            regMethod = "lookup";
            return regMethod;
        }

        for(String methodName : supported ) {

            if( signature.contains(methodName) ) {
                regMethod = methodName;
                return methodName;
            }
        }

        Logger.eprintlnMixedYellow("Unsupported registry method:", signature);
        Logger.eprintlnMixedBlue("Support values are", String.join(", ", supported));
        RMGUtils.exit();

        return null;
    }

    /**
     * During DGC related rmg operations, users can select the DGC method
     * that is used for the different RMI calls. This is done by using the --signature
     * option. In contrast to custom remote objects, the method signature is not really
     * parsed. It is only checked for specific keywords and the corresponding DGC
     * methods signature is chosen automatically.
     *
     * @return method to use for DGC operations - if valid.
     */
    public String getDgcMethod()
    {
        if( dgcMethod != null )
            return dgcMethod;

        String signature = RMGOption.SIGNATURE.getString();

        if( signature == null ) {
            dgcMethod = "clean";
            return dgcMethod;
        }

        for(String methodName : new String[]{"clean", "dirty"} ) {

            if( signature.contains(methodName) ) {
                dgcMethod = methodName;
                return methodName;
            }
        }

        Logger.eprintlnMixedYellow("Unsupported DGC method:", signature);
        Logger.eprintMixedBlue("Support values are", "clean", "and ");
        Logger.printlnPlainBlue("dirty");
        RMGUtils.exit();

        return null;
    }

    /**
     * Parse the user specified --component value. This function verifies that the value
     * matches one of the supported values: act, dgc or reg.
     *
     * @return user specified component value - if valid.
     */
    public RMIComponent getComponent()
    {
        if( component != null )
            return component;

        RMIComponent targetComponent = RMIComponent.getByShortName(RMGOption.COMPONENT.getString());

        if( targetComponent == null )
            return null;

        switch( targetComponent ) {

            case REGISTRY:
            case DGC:
            case ACTIVATOR:
                break;

            default:
                Logger.eprintlnMixedYellow("Unsupported RMI component:", RMGOption.COMPONENT.getString());
                Logger.eprintMixedBlue("Supported values are", RMIComponent.ACTIVATOR.shortName + ", " + RMIComponent.DGC.shortName, "and ");
                Logger.printlnPlainBlue(RMIComponent.REGISTRY.shortName);
                RMGUtils.exit();
        }

        component = targetComponent;

        return targetComponent;
    }

    /**
     * Determines whether the user specified method signature needs to be parsed. This is the case
     * if the user attacks either a bound name or an ObjID. In these cases the --signature option needs
     * to contain a valid method signature.
     *
     * @return true if the value in --signature needs to be parsed
     */
    public boolean parseMethodSignature()
    {
        return RMGOption.SIGNATURE.notNull() && ( RMGOption.BOUND_NAME.notNull() || RMGOption.OBJID.notNull() );
    }

    /**
     * Utility function that returns the hostname specified on the command line.
     *
     * @return user specified hostname (target)
     */
    public String getHost()
    {
        return this.getPositionalString(0);
    }

    /**
     * Utility function that returns the port specified on the command line.
     *
     * @return user specified port (target)
     */
    public int getPort()
    {
        if( action != null && action == Operation.SCAN)
            return 0;

        return this.getPositionalInt(1);
    }

    /**
     * Parses the user specified gadget arguments to request a corresponding gadget from the PayloadProvider.
     * The corresponding gadget object is returned.
     *
     * @return gadget object build from the user specified arguments
     */
    public Object getGadget()
    {
        String gadget = this.getPositionalString(3);
        String command = null;

        if(this.getArgumentCount() > 4)
            command = this.getPositionalString(4);

        return PluginSystem.getPayloadObject(this.getAction(), gadget, command);
    }

    /**
     * Parses the user specified argument string during a call action. Passes the string to the corresponding
     * ArgumentProvider and returns the result argument array.
     *
     * @return Object array resulting from the specified argument string
     */
    public Object[] getCallArguments()
    {
        String argumentString = this.getPositionalString(3);
        return PluginSystem.getArgumentArray(argumentString);
    }

    /**
     * Is used when the enum action was specified. The enum action allows users to limit enumeration to certain
     * scan actions. This function parses the user supplied arguments and checks which scan actions were requested.
     * The corresponding actions are returned as EnumSet.
     *
     * @return EnumSet of ScanAction which were requested by the user.
     */
    public EnumSet<ScanAction> getScanActions()
    {
        int argumentCount = this.getArgumentCount();

        if( argumentCount <= 3 )
            return EnumSet.allOf(ScanAction.class);

        return ScanAction.parseScanActions(argList.subList(3, argumentCount));
    }

    /**
     * Is used to parse the port specification for the scan operation. For the scan operation, the
     * second port argument can be a range (e.g. 10-1000), a list (e.g. 1090-1099,9000,9010) a single
     * port (e.g. 1090) or the keyword "-" (scans all rmi ports configured in the config file). This
     * function parses the user specified value and returns the corresponding int array that needs to
     * be scanned.
     *
     * @return array of int which contains all ports that should be scanned
     */
    public int[] getRmiPots()
    {
        Set<Integer> rmiPorts = new HashSet<Integer>();

        String argPort = getPositionalString(1);
        String portString = config.getProperty("rmi-ports");

        if( argPort.equals("-") && portString != null ) {

            addPorts(portString, rmiPorts);

        } else {

            addPorts(argPort, rmiPorts);

            for(int ctr = 3; ctr < getArgumentCount(); ctr++) {

                argPort = getPositionalString(ctr);

                if( argPort.equals("-") )
                    addPorts(portString, rmiPorts);

                else
                    addPorts(argPort, rmiPorts);
            }
        }

        return rmiPorts.stream().mapToInt(i->i).toArray();
    }

    /**
     * Helper function that handles port lists.
     *
     * @param portString user specified port string
     * @param portList Set of Integer where parsed ports are added
     */
    public void addPorts(String portString, Set<Integer> portList)
    {
        String[] ports = portString.split(",");

        for(String port: ports) {
            addRange(port, portList);
        }
    }

    /**
     * Helper function that handles port ranges.
     *
     * @param portString user specified port string
     * @param portList Set of Integer where parsed ports are added
     */
    public void addRange(String portRange, Set<Integer> portList)
    {
        try {

            if(!portRange.contains("-")) {
                portList.add(Integer.valueOf(portRange));
                return;
            }

            String[] split = portRange.split("-");
            int start = Integer.valueOf(split[0]);
            int end = Integer.valueOf(split[1]);

            for(int ctr = start; ctr <= end; ctr++)
                portList.add(ctr);

        } catch( java.lang.NumberFormatException | java.lang.ArrayIndexOutOfBoundsException e ) {
            Logger.eprintlnMixedYellow("Caught unexpected", e.getClass().getSimpleName(), "while parsing RMI ports.");
            Logger.eprintlnMixedBlue("The specified value", portRange, "is invalid.");
            RMGUtils.exit();
        }
    }

    /**
     * Is called when the scan action was requested. Sets custom timeout values for RMI socket
     * operations, as the default values are not well suited for portscanning.
     *
     * This function needs to be called early before the corresponding RMI classes are loaded.
     */
    public void setSocketTimeout()
    {
        String scanTimeoutConnect = config.getProperty("scan-timeout-connect");
        String scanTimeoutRead = config.getProperty("scan-timeout-read");

        System.setProperty("sun.rmi.transport.connectionTimeout", scanTimeoutConnect);
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", scanTimeoutRead);
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", scanTimeoutRead);

        PortScanner.setSocketTimeouts(scanTimeoutRead, scanTimeoutConnect);
    }
}
