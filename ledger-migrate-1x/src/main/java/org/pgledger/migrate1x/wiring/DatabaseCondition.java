package org.pgledger.migrate1x.wiring;

import org.pgledger.database.driver.DriverRegisteredCondition;
import org.pgledger.migrate1x.tool.ToolCommand;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * {@link DriverRegisteredCondition} for the migration tool: the database beans exist only for a command that uses the database.
 * The first operand is the command (Boot's command line property source names the operands this way).
 */
public final class DatabaseCondition extends DriverRegisteredCondition {

    @Override
    protected boolean needsDatabase(ConditionContext context) {
        return ToolCommand.parse(operands(context).split(",")[0]).filter(command -> !command.usesDatabase()).isEmpty();
    }

    /**
     * The operands as they were given: read from the property sources without resolving placeholders, because an operand may
     * hold {@code ${...}} as text and {@code Environment#getProperty} would refuse it as an unresolvable placeholder.
     */
    private static String operands(ConditionContext context) {
        if (context.getEnvironment() instanceof ConfigurableEnvironment environment) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                Object operands = source.getProperty(CommandLinePropertySource.DEFAULT_NON_OPTION_ARGS_PROPERTY_NAME);
                if (operands != null) {
                    return operands.toString();
                }
            }
        }
        return "";
    }
}
