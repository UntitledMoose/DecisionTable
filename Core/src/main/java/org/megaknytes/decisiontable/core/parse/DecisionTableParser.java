package org.megaknytes.decisiontable.core.parse;

import org.megaknytes.decisiontable.core.DecisionTable;
import org.megaknytes.decisiontable.core.SystemConfiguration;
import org.megaknytes.decisiontable.core.rule.address.Address;
import org.megaknytes.decisiontable.core.rule.Rule;
import org.megaknytes.decisiontable.core.rule.Action;
import org.megaknytes.decisiontable.core.rule.expression.logical.AlwaysExpression;
import org.megaknytes.decisiontable.core.rule.expression.logical.AndExpression;
import org.megaknytes.decisiontable.core.rule.expression.comparison.ComparisonExpression;
import org.megaknytes.decisiontable.core.rule.expression.ConditionExpression;
import org.megaknytes.decisiontable.core.rule.expression.logical.NotExpression;
import org.megaknytes.decisiontable.core.rule.expression.logical.OnceExpression;
import org.megaknytes.decisiontable.core.rule.expression.logical.OrExpression;
import org.megaknytes.decisiontable.core.rule.expression.logical.XOrExpression;
import org.megaknytes.decisiontable.core.rule.value.type.State;
import org.megaknytes.decisiontable.core.rule.value.ValueParserRegistry;
import org.megaknytes.decisiontable.core.rule.value.Value;
import org.megaknytes.decisiontable.core.rule.value.ValueParser;
import org.megaknytes.decisiontable.core.utils.exception.XmlParseException;
import org.megaknytes.decisiontable.core.utils.xml.XmlElements;
import org.megaknytes.decisiontable.core.utils.exception.ConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Responsible for loading a file containing Decision Table XML into a Decision Table object
 */
public class DecisionTableParser {
    private final ValueParser valueParser;

    public DecisionTableParser(ValueParserRegistry valueParserRegistry) {
        this.valueParser = new ValueParser(valueParserRegistry);
    }

    /**
     * Given a file containing Decision Table XML and its System Configuration, attempt to load rules
     *
     * @param file the file that contains the Decision Table XML
     * @param configuration the System Configuration as named in the Decision Table
     * @return a Decision Table object matching the given XML file
     */
    public DecisionTable load(File file, SystemConfiguration configuration) {
        Document document;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(file);

            document.getDocumentElement().normalize();
        } catch (IOException | SAXException | ParserConfigurationException e) {
            throw new XmlParseException("Failed to parse " + file.getName() + ": " + e.getMessage());
        }

        Element root = document.getDocumentElement();
        String name = root.getAttribute("name");

        List<Rule> rules = new ArrayList<>();

        for (Element rulesSection : XmlElements.childElementsNamed(root, "Rules")) {
            for (Element child : XmlElements.childElements(rulesSection)) {
                // Iterate over the RuleGroup to discover each nested rule.
                if (child.getTagName().equals("RuleGroup")) {
                    String groupName = child.getAttribute("name");
                    String enabledAttribute = XmlElements.optionalAttribute(child, "enabled");
                    boolean groupEnabled = enabledAttribute == null || parseBoolean(enabledAttribute);

                    if (!groupEnabled) {
                        continue;
                    }

                    for (Element ruleElement : XmlElements.childElementsNamed(child, "Rule")) {
                        rules.add(parseRule(ruleElement.getAttribute("name"), groupName, ruleElement, configuration));
                    }
                } else {
                    rules.add(parseRule(child.getAttribute("name"), null, child, configuration));
                }
            }
        }

        return new DecisionTable(name, rules);
    }

    /**
     * Parses a Rule XML element into a Rule object, including its condition and actions
     *
     * @param ruleName the rule's name attribute
     * @param groupName the RuleGroup name, or null if the rule is not grouped
     * @param ruleElement the Rule XML element to parse
     * @param configuration the loaded System Configuration used to resolve target types, readers, and writers
     * @return the parsed Rule, including its condition and actions
     */
    private Rule parseRule(String ruleName, String groupName, Element ruleElement, SystemConfiguration configuration) {
        String priorityAttribute = XmlElements.optionalAttribute(ruleElement, "priority");
        int priority = priorityAttribute != null ? Integer.parseInt(priorityAttribute) : 0;

        Element conditionElement = XmlElements.childElementsNamed(ruleElement, "Condition").get(0);
        Element actionElement = XmlElements.childElementsNamed(ruleElement, "Action").get(0);

        List<ConditionExpression> conditionChildren = parseExpressionList(XmlElements.childElements(conditionElement), configuration);
        // If multiple conditions exist and aren't wrapped in an AndExpression, do so for the user
        ConditionExpression condition = conditionChildren.size() == 1 ? conditionChildren.get(0) : new AndExpression(conditionChildren);

        List<Action> actions = new ArrayList<>();

        for (Element setElement : XmlElements.childElementsNamed(actionElement, "Set")) {
            Address address = Address.parse(setElement.getAttribute("target"));
            Class<?> type = configuration.getAddressType(address);

            String errorContext = "Set target=\"" + address + "\"";
            Supplier<Object> valueSupplier = getSupplier(setElement, address, type, errorContext, configuration);

            actions.add(new Action(address, configuration.getWriter(address), valueSupplier));
        }

        return new Rule(ruleName, groupName, priority, condition, actions);
    }

    /**
     * Parses a Condition XML element into a ConditionExpression
     *
     * @param element the XML element to parse
     * @param configuration the system configuration for the table
     * @return the ConditionExpression
     */
    private ConditionExpression parseExpression(Element element, SystemConfiguration configuration) {
        switch (element.getTagName()) {
            // These cases are nestable, and therefore are recursive
            case "And":
                return new AndExpression(parseExpressionList(XmlElements.childElements(element), configuration));
            case "Or":
                return new OrExpression(parseExpressionList(XmlElements.childElements(element), configuration));
            case "XOr":
                return new XOrExpression(parseExpressionList(XmlElements.childElements(element), configuration));
            case "Not":
                return new NotExpression(parseExpression(XmlElements.childElements(element).get(0), configuration));
            // Useful for actions that always need to run (ex. drivetrain) or only need to run once (ex. initialization)
            case "Always":
                return new AlwaysExpression();
            case "Once":
                return new OnceExpression();
            // Comparison between objects
            case "Equals":
                return parseComparison(element, ComparisonExpression.Operator.EQUALS, configuration);
            case "NotEquals":
                return parseComparison(element, ComparisonExpression.Operator.NOT_EQUALS, configuration);
            case "GreaterThan":
                return parseComparison(element, ComparisonExpression.Operator.GREATER_THAN, configuration);
            case "LessThan":
                return parseComparison(element, ComparisonExpression.Operator.LESS_THAN, configuration);
            case "GreaterOrEqual":
                return parseComparison(element, ComparisonExpression.Operator.GREATER_OR_EQUAL, configuration);
            case "LessOrEqual":
                return parseComparison(element, ComparisonExpression.Operator.LESS_OR_EQUAL, configuration);
            default:
                throw new ConfigurationException("Unknown condition : <" + element.getTagName() + ">, check your file.");
        }
    }

    private List<ConditionExpression> parseExpressionList(List<Element> elements, SystemConfiguration configuration) {
        List<ConditionExpression> result = new ArrayList<>();

        for (Element element : elements) {
            result.add(parseExpression(element, configuration));
        }

        return result;
    }

    /**
     * Parses a Comparison XML element into a ComparisonExpression
     *
     * @param element the comparison XML element with a target and a value or reference
     * @param operator the comparison operator
     * @param configuration the System Configuration named by the table
     * @return a ComparisonExpression that compares the target's current value against the expected value
     */
    private ConditionExpression parseComparison(Element element, ComparisonExpression.Operator operator, SystemConfiguration configuration) {
        Address address = Address.parse(element.getAttribute("target"));
        Class<?> type = configuration.getAddressType(address);
        Supplier<Object> actual = configuration.getReader(address);

        String errorContext = operator + " target=\"" + address + "\"";
        Supplier<Object> expected = getSupplier(element, address, type, errorContext, configuration);

        return new ComparisonExpression(actual, operator, expected);
    }

    /**
     * Get the supplier for an XML element
     * <p>
     * The element's value or reference attribute determines where the supplier is directed.
     *
     * @param element an XML element that contains a value or reference attribute
     * @param address the address that the value will be used with
     * @param type the expected type of the value
     * @param errorContext a description of the current parse location for error messages
     * @param configuration the loaded system configuration
     * @return a supplier that gets the current value
     */
    private Supplier<Object> getSupplier(Element element, Address address, Class<?> type, String errorContext, SystemConfiguration configuration) {
        String value = XmlElements.optionalAttribute(element, "value");
        String ref = XmlElements.optionalAttribute(element, "ref");

        if (type.equals(State.class) && value != null && ref == null) {
            State state = configuration.getDeclaredState(address, value, errorContext);
            return () -> state;
        }

        Value<?> source = valueParser.parse(value, element, ref, castToType(type), errorContext);

        if (source.getReferenceAddress()) {
            return configuration.getReader(source.getReference());
        }

        // If the source is a literal, provide a supplier that returns the literal value
        return source::getLiteral;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> castToType(Class<?> type) {
        return (Class<T>) type;
    }

    private static boolean parseBoolean(String literal) {
        return "true".equals(literal) || "1".equals(literal);
    }
}