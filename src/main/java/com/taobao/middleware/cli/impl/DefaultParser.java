/*
 *  Copyright (c) 2011-2015 The original author or authors
 *  ------------------------------------------------------
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *       The Eclipse Public License is available at
 *       http://www.eclipse.org/legal/epl-v10.html
 *
 *       The Apache License v2.0 is available at
 *       http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package com.taobao.middleware.cli.impl;

import com.taobao.middleware.cli.AmbiguousOptionException;
import com.taobao.middleware.cli.Argument;
import com.taobao.middleware.cli.CLI;
import com.taobao.middleware.cli.CLIException;
import com.taobao.middleware.cli.CommandLine;
import com.taobao.middleware.cli.CommandLines;
import com.taobao.middleware.cli.InvalidValueException;
import com.taobao.middleware.cli.MissingOptionException;
import com.taobao.middleware.cli.MissingValueException;
import com.taobao.middleware.cli.Option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * The default implementation of the command line parser.
 * Absolutely not thread safe!
 *
 * @author Clement Escoffier <clement@apache.org>
 */
public class DefaultParser {

    protected String token;
    protected Option current;
    protected List<Option> expectedOpts;

    private DefaultCommandLine commandLine;
    private boolean skipParsing;
    private CLI cli;

    protected boolean caseSensitive;

    /**
     * Remove the hyphens from the beginning of <code>str</code> and
     * return the new String.
     *
     * @param str The string from which the hyphens should be removed.
     * @return the new String.
     */
    static String stripLeadingHyphens(String str) {
        if (str == null) {
            return null;
        }
        if (str.startsWith("--")) {
            return str.substring(2, str.length());
        } else if (str.startsWith("-")) {
            return str.substring(1, str.length());
        }

        return str;
    }

    /**
     * Remove the leading and trailing quotes from <code>str</code>.
     * E.g. if str is '"one two"', then 'one two' is returned.
     *
     * @param str The string from which the leading and trailing quotes
     *            should be removed.
     * @return The string without the leading and trailing quotes.
     */
    static String stripLeadingAndTrailingQuotes(String str) {
        int length = str.length();
        if (length > 1 && str.startsWith("\"") && str.endsWith("\"") && str.substring(1, length - 1).indexOf('"') == -1) {
            str = str.substring(1, length - 1);
        }

        return str;
    }

    public CommandLine parse(CLI cli, List<String> cla)
            throws CLIException {
        return parse(cli, cla, true);
    }

    public CommandLine parse(CLI cli, List<String> cla, boolean validate)
            throws CLIException {
        commandLine = (DefaultCommandLine) CommandLines.create(cli);
        current = null;
        skipParsing = false;
        this.cli = cli;

        // Automatic numbering of argument if not set
        int current = 0;
        for (Argument argument : cli.getArguments()) {
            if (argument.getIndex() == -1) {
                argument.setIndex(current);
                current++;
            } else {
                current = argument.getIndex() + 1;
            }
        }

        // Sort the argument by index.
        Collections.sort(cli.getArguments(), new ArgumentComparator());

        // Check argument and option validity
        for (Option option : cli.getOptions()) {
            option.ensureValidity();
        }

        for (Argument argument : cli.getArguments()) {
            argument.ensureValidity();
        }

        // Extract the list of required options.
        // Every time an option get a value, it is removed from the list.
        expectedOpts = getRequiredOptions();

        if (cla != null) {
            for (String s : cla) {
                visit(s);
            }
        }

        try {
            // check the values of the last option
            checkRequiredValues();

            // check that all required options has a value
            checkRequiredOptions();

            // Call global validation.
            validate();
            commandLine.setValidity(true);
        } catch (CLIException e) {
            if (validate && !commandLine.isAskingForHelp()) {
                throw e;
            } else {
                commandLine.setValidity(false);
            }
        }

        return commandLine;
    }

    protected void validate() throws CLIException {


        // Check that only the last argument is multi-values and that index are unique
        boolean multiValue = false;
        List<Integer> usedIndexes = new ArrayList<Integer>();
        for (Argument argument : cli.getArguments()) {
            if (usedIndexes.contains(argument.getIndex())) {
                throw new CLIException("Only one argument can use the index " + argument.getIndex());
            }
            usedIndexes.add(argument.getIndex());
            if (multiValue) {
                throw new CLIException("Only the last argument can be multi-valued");
            }
            multiValue = argument.isMultiValued();
        }

        // Add value to the specified arguments
        Iterator<Argument> iterator = cli.getArguments().iterator();
        Argument current = null;
        if (iterator.hasNext()) {
            current = iterator.next();
        }
        for (String v : commandLine.allArguments()) {
            if (current != null) {
                commandLine.setRawValue(current, v);
                if (!current.isMultiValued()) {
                    if (iterator.hasNext()) {
                        current = iterator.next();
                    } else {
                        current = null;
                    }
                }
            }
        }

        for (Argument arg : cli.getArguments()) {
            if (arg.isRequired() && !commandLine.isArgumentAssigned(arg)) {
                throw new MissingValueException(arg);
            }
        }
    }

    private List<Option> getRequiredOptions() {
        List<Option> options = new LinkedList<Option>();
        for (Option option : cli.getOptions()) {
            if (option.isRequired()) {
                options.add(option);
            }
        }
        return options;
    }

    private void checkRequiredOptions() throws MissingOptionException {
        // if there are required options that have not been processed
        if (!expectedOpts.isEmpty()) {
            throw new MissingOptionException(expectedOpts);
        }
    }

    private void checkRequiredValues() throws MissingValueException {
        if (current != null) {
            if (current.acceptValue() && !commandLine.isOptionAssigned(current) && !current.isFlag()) {
                throw new MissingValueException(current);
            }
        }
    }

    private void visit(String token) throws CLIException {
        this.token = token;
        if (skipParsing) {
            commandLine.addArgumentValue(token);
        } else if (token.equals("--")) {
            skipParsing = true;
        } else if (current != null && current.acceptValue() && isValue(token)) {
            commandLine.addRawValue(current, stripLeadingAndTrailingQuotes(token));
        } else if (token.startsWith("--")) {
            handleLongOption(token);
        } else if (token.startsWith("-") && !"-".equals(token)) {
            handleShortAndLongOption(token);
        } else {
            handleArgument(token);
        }

        if (current != null && !commandLine.acceptMoreValues(current)) {
            current = null;
        }
    }

    /**
     * Returns true is the token is a valid value.
     *
     * @param token the token
     * @return {@code true} if the token represents a value
     */
    private boolean isValue(String token) {
        return !isOption(token) || isNegativeNumber(token);
    }

    /**
     * Check if the token is a negative number.
     *
     * @param token the token
     * @return {@code true} if the token represents a negative number
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private boolean isNegativeNumber(String token) {
        try {
            Double.parseDouble(token);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Tells if the token looks like an option.
     *
     * @param token the token
     * @return {@code true} if the token represents an option
     */
    private boolean isOption(String token) {
        return isLongOption(token) || isShortOption(token);
    }

    /**
     * Tells if the token looks like a short option.
     *
     * @param token the token
     * @return {@code true} if the token represents an option (short name match)
     */
    private boolean isShortOption(String token) {
        // short options (-S, -SV, -S=V, -SV1=V2, -S1S2)
        return token.startsWith("-") && token.length() >= 2 && hasOptionWithShortName(token.substring(1, 2));
    }

    /**
     * Tells if the token looks like a long option.
     *
     * @param token the token
     * @return {@code true} if the token represents an option (long name match)
     */
    private boolean isLongOption(String token) {
        if (!token.startsWith("-") || token.length() == 1) {
            return false;
        }

        int pos = token.indexOf("=");
        String t = pos == -1 ? token : token.substring(0, pos);

        if (!getMatchingOptions(t).isEmpty()) {
            // long or partial long options (--L, -L, --L=V, -L=V, --l, --l=V)
            return true;
        } else if (getLongPrefix(token) != null && !token.startsWith("--")) {
            // -LV
            return true;
        }

        return false;
    }

    private void handleArgument(String token) {
        commandLine.addArgumentValue(token);
    }

    /**
     * Handles the following tokens:
     * <p/>
     * --L
     * --L=V
     * --L V
     * --l
     *
     * @param token the command line token to handle
     */
    private void handleLongOption(String token) throws CLIException {
        if (token.indexOf('=') == -1) {
            handleLongOptionWithoutEqual(token);
        } else {
            handleLongOptionWithEqual(token);
        }
    }

    /**
     * Handles the following tokens:
     * <p/>
     * --L
     * -L
     * --l
     * -l
     *
     * @param token the command line token to handle
     */
    private void handleLongOptionWithoutEqual(String token) throws CLIException {
        List<Option> matchingOpts = getMatchingOptions(token);
        if (matchingOpts.isEmpty()) {
            handleArgument(token);
        } else if (matchingOpts.size() > 1) {
            throw new AmbiguousOptionException(token, matchingOpts);
        } else {
            final Option option = matchingOpts.get(0);
            handleOption(option);
        }
    }

    /**
     * Handles the following tokens:
     * <p/>
     * --L=V
     * -L=V
     * --l=V
     * -l=V
     *
     * @param token the command line token to handle
     */
    private void handleLongOptionWithEqual(String token) throws CLIException {
        int pos = token.indexOf('=');

        String value = token.substring(pos + 1);

        String opt = token.substring(0, pos);

        List<Option> matchingOpts = getMatchingOptions(opt);
        if (matchingOpts.isEmpty()) {
            handleArgument(token);
        } else if (matchingOpts.size() > 1) {
            throw new AmbiguousOptionException(opt, matchingOpts);
        } else {
            Option option = matchingOpts.get(0);
            if (commandLine.acceptMoreValues(option)) {
                handleOption(option);
                commandLine.addRawValue(option, value);
                current = null;
            } else {
                throw new InvalidValueException(option, value);
            }
        }
    }

    /**
     * Handles the following tokens:
     * <p/>
     * -S
     * -SV
     * -S V
     * -S=V
     * <p/>
     * -L
     * -LV
     * -L V
     * -L=V
     * -l
     *
     * @param token the command line token to handle
     */
    private void handleShortAndLongOption(String token) throws CLIException {
        String t = stripLeadingHyphens(token);
        int pos = t.indexOf('=');

        if (t.length() == 1) {
            // -S
            if (hasOptionWithShortName(t)) {
                handleOption(getOption(t));
            } else {
                handleArgument(token);
            }
        } else if (pos == -1) {
            // no equal sign found (-xxx)
            if (hasOptionWithShortName(t)) {
                handleOption(getOption(t));
            } else if (!getMatchingOptions(t).isEmpty()) {
                // -L or -l
                handleLongOptionWithoutEqual(token);
            } else {
                // look for a long prefix (-Xmx512m)
                String opt = getLongPrefix(t);
                if (opt != null) {
                    if (commandLine.acceptMoreValues(getOption(opt))) {
                        handleOption(getOption(opt));
                        commandLine.addRawValue(getOption(opt), t.substring(opt.length()));
                        current = null;
                    } else {
                        throw new InvalidValueException(getOption(opt), t.substring(opt.length()));
                    }
                } else if (isAValidShortOption(t)) {
                    // -SV1 (-Dflag)
                    String strip = t.substring(0, 1);
                    Option option = getOption(strip);
                    handleOption(option);
                    commandLine.addRawValue(current, t.substring(1));
                    current = null;
                } else {
                    // -S1S2S3 or -S1S2V
                    handleConcatenatedOptions(token);
                }
            }
        } else {
            // equal sign found (-xxx=yyy)
            String opt = t.substring(0, pos);
            String value = t.substring(pos + 1);
            if (opt.length() == 1) {
                // -S=V
                Option option = getOption(opt);
                if (option != null) {
                    if (commandLine.acceptMoreValues(option)) {
                        handleOption(option);
                        commandLine.addRawValue(option, value);
                        current = null;
                    } else {
                        throw new InvalidValueException(option, value);
                    }
                } else {
                    handleArgument(token);
                }
            } else if (isAValidShortOption(opt) && !hasOptionWithLongName(opt)) {
                // -SV1=V2 (-Dkey=value)
                handleOption(getOption(opt.substring(0, 1)));
                commandLine.addRawValue(current, opt.substring(1) + "=" + value);
                current = null;
            } else {
                // -L=V or -l=V
                handleLongOptionWithEqual(token);
            }
        }
    }

    /**
     * Search for a prefix that is the long name of an option (-Xmx512m)
     *
     * @param token the token
     * @return the found prefix.
     */
    private String getLongPrefix(String token) {
        String t = stripLeadingHyphens(token);

        int i;
        String opt = null;
        for (i = t.length() - 2; i > 1; i--) {
            String prefix = t.substring(0, i);
            if (hasOptionWithLongName(prefix)) {
                opt = prefix;
                break;
            }
        }
        return opt;
    }

    private boolean optionEauals(String name1, String name2) {
        if (this.caseSensitive) {
            return name1.equals(name2);
        } else {
            return name1.equalsIgnoreCase(name2);
        }
    }

    private boolean hasOptionWithLongName(String name) {
        for (Option option : cli.getOptions()) {
            if (optionEauals(name, option.getLongName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOptionWithShortName(String name) {
        for (Option option : cli.getOptions()) {
            if (optionEauals(name, option.getShortName())) {
                return true;
            }
        }
        return false;
    }

    private void handleOption(Option option) throws CLIException {
        // check the previous option before handling the next one
        checkRequiredValues();
        updateRequiredOptions(option);
        //IMPORTANT for flag we must set this attributes as it will determine the value out of it.
        commandLine.setSeenInCommandLine(option);
        if (commandLine.acceptMoreValues(option)) {
            current = option;
        } else {
            current = null;
        }
    }

    /**
     * Removes the option from the list of expected elements.
     *
     * @param option the option
     */
    private void updateRequiredOptions(Option option) {
        if (option.isRequired()) {
            expectedOpts.remove(option);
        }
    }

    /**
     * Retrieve the {@link Option} matching the long or short name specified.
     * The leading hyphens in the name are ignored (up to 2).
     *
     * @param opt short or long name of the {@link Option}
     * @return the option represented by opt
     */
    public Option getOption(String opt) {
        opt = stripLeadingHyphens(opt);
        for (Option option : cli.getOptions()) {
            if (optionEauals(opt, option.getShortName()) || optionEauals(opt, option.getLongName())) {
                return option;
            }
        }
        return null;
    }

    private boolean isAValidShortOption(String token) {
        String opt = token.substring(0, 1);
        Option option = getOption(opt);
        return option != null && commandLine.acceptMoreValues(option);
    }

    /**
     * Returns the options with a long name starting with the name specified.
     *
     * @param opt the partial name of the option
     * @return the options matching the partial name specified, or an empty list if none matches
     */
    public List<Option> getMatchingOptions(String opt) {
        opt = stripLeadingHyphens(opt);

        List<Option> matching = new ArrayList<Option>();


        final List<Option> options = cli.getOptions();

        // Exact match first
        for (Option option : options) {
            if (optionEauals(opt, option.getLongName())) {
                return Collections.singletonList(option);
            }
        }

        for (Option option : options) {
            if (option.getLongName() != null && option.getLongName().startsWith(opt)) {
                matching.add(option);
            }
        }

        return matching;
    }

    protected void handleConcatenatedOptions(String token) throws CLIException {
        for (int i = 1; i < token.length(); i++) {
            String ch = String.valueOf(token.charAt(i));

            if (hasOptionWithShortName(ch)) {
                handleOption(getOption(ch));

                if (current != null && token.length() != i + 1) {
                    // add the trail as an argument of the option
                    commandLine.addRawValue(current, token.substring(i + 1));
                    break;
                }
            } else {
                handleArgument(token);
                break;
            }
        }
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }
}
