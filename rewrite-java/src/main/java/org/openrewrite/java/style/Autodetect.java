/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.style;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Tree;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.style.GeneralFormatStyle;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.style.Style;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;

public class Autodetect extends NamedStyles {
    @JsonCreator
    public Autodetect(UUID id, Collection<Style> styles) {
        super(id,
                "org.openrewrite.java.Autodetect",
                "Auto-detected",
                "Automatically detect styles from a repository's existing code.",
                emptySet(), styles);
    }

    public static Autodetect detect(List<? extends JavaSourceFile> cus) {
        Builder builder = builder();
        for (JavaSourceFile cu : cus) {
            builder.phase1(cu);
        }
        for (JavaSourceFile cu : cus) {
            builder.phase2(cu);
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final IndentStatistics indentStatistics = new IndentStatistics();
        private final ImportLayoutStatistics importLayoutStatistics = new ImportLayoutStatistics();
        private final SpacesStatistics spacesStatistics = new SpacesStatistics();
        private final WrappingAndBracesStatistics wrappingAndBracesStatistics = new WrappingAndBracesStatistics();
        private final GeneralFormatStatistics generalFormatStatistics = new GeneralFormatStatistics();
        private final NavigableSet<String> importedPackages = new TreeSet<>();

        /**
         * Accumulate information about style one source file at a time.
         * @param cu A JVM source file.
         */
        public void phase1(JavaSourceFile cu) {
            for (J.Import anImport : cu.getImports()) {
                importedPackages.add(anImport.getPackageName() + ".");
            }
            importLayoutStatistics.mapBlockPatterns(importedPackages);
        }

        public void phase2(JavaSourceFile cu) {
            new FindIndentJavaVisitor().visitNonNull(cu, indentStatistics);
            new FindImportLayout().visitNonNull(cu, importLayoutStatistics);
            new FindSpacesStyle().visitNonNull(cu, spacesStatistics);
            new FindWrappingAndBracesStyle().visitNonNull(cu, wrappingAndBracesStatistics);
            new FindLineFormatJavaVisitor().visitNonNull(cu, generalFormatStatistics);
        }

        public Autodetect build() {
            return new Autodetect(Tree.randomId(), Arrays.asList(
                    indentStatistics.getTabsAndIndentsStyle(),
                    importLayoutStatistics.getImportLayoutStyle(),
                    spacesStatistics.getSpacesStyle(),
                    wrappingAndBracesStatistics.getWrappingAndBracesStyle(),
                    generalFormatStatistics.getFormatStyle()));
        }
    }

    private static class GeneralFormatStatistics {
        private int linesWithCRLFNewLines = 0;
        private int linesWithLFNewLines = 0;

        public boolean isIndentedWithLFNewLines() {
            return linesWithLFNewLines >= linesWithCRLFNewLines;
        }

        public GeneralFormatStyle getFormatStyle() {
            boolean useCRLF = !isIndentedWithLFNewLines();

            return new GeneralFormatStyle(useCRLF);
        }
    }

    @Data
    private static class IndentStatistic {
        @Value
        private static class DepthCoordinate {
            int indentDepth;
            int continuationDepth;
        }
        // depth -> count of whitespace char (4 spaces, 2 tabs, etc) -> count of occurrences
        Map<DepthCoordinate, Map<Integer, Long>> depthToSpaceIndentFrequencies = new ConcurrentHashMap<>();

        public void record(int indentDepth, int continuationDepth, int charCount) {
            record(new DepthCoordinate(indentDepth, continuationDepth), charCount);
        }

        public void record(DepthCoordinate depth, int charCount) {
            if(charCount <= 0) {
                return;
            }
            depthToSpaceIndentFrequencies.compute(depth, (n, map) -> {
                if(map == null) {
                    map = new ConcurrentHashMap<>();
                }
                map.compute(charCount, (m, count) -> {
                    if(count == null) {
                        count = 0L;
                    }
                    return count + 1;
                });
                return map;
            });
        }

        /**
         * Determines the most frequent indentation. Assumes that indentation is a multiple of depth, which is true for
         * normal indentation but not usually true for continuation indents.
         */
        public int commonIndent() {
            Map<Integer, Long> map = depthToSpaceIndentFrequencies.entrySet().stream()
                    .flatMap(it -> {
                        int depth = it.getKey().getIndentDepth();
                        return it.getValue().entrySet()
                                .stream()
                                .map(charsToOccurrence -> new AbstractMap.SimpleEntry<>(
                                        charsToOccurrence.getKey() / depth,
                                        charsToOccurrence.getValue()));
                    })
                    .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, Long::sum));

            return map.entrySet().stream().max(Comparator.comparingLong(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(4); // IntelliJ default
        }

        /**
         * Use the provided common indentation to interpret this IndentStatistic's contents as continuation indents.
         * Continuation indents are assumed to be offset by some constant number of characters from what their depth
         * would normally indicate.
         */
        public int continuationIndent(int commonIndent) {
            Map<Integer, Long> map = depthToSpaceIndentFrequencies.entrySet().stream()
                    .flatMap(it -> {
                        int depth = it.getKey().getIndentDepth();
                        int continuationDepth = it.getKey().getContinuationDepth();
                        return it.getValue().entrySet()
                                .stream()
                                .map(charsToOccurrence -> new AbstractMap.SimpleEntry<>(
                                            (charsToOccurrence.getKey() - (depth * commonIndent)) / continuationDepth,
                                        charsToOccurrence.getValue()));
                    })
                    .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, Long::sum));

            return map.entrySet().stream().max(Comparator.comparingLong(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(commonIndent * 2);
        }
    }

    private static class IndentStatistics {
        private final IndentStatistic spaceIndentFrequencies = new IndentStatistic();
        private final IndentStatistic spaceContinuationIndentFrequencies = new IndentStatistic();
        private final IndentStatistic tabIndentFrequencies = new IndentStatistic();
        private final IndentStatistic tabContinuationIndentFrequencies = new IndentStatistic();
        private int linesWithSpaceIndents = 0;
        private int linesWithTabIndents = 0;

        private int multilineAlignedToFirstArgument = 0;
        private int multilineNotAlignedToFirstArgument = 0;

        private int depth = 0;
        private int continuationDepth = 1;
        public int getDepth() {
            return depth;
        }
        public int getContinuationDepth() {
            return continuationDepth;
        }

        public void incrementDepth() {
            depth++;
        }

        public void decrementDepth() {
            depth--;
        }

        // Leave these unused methods in case we want to extend autodetection to method invocation argument lists
        @SuppressWarnings("unused")
        public void incrementContinuationDepth() {
            continuationDepth++;
        }

        @SuppressWarnings("unused")
        public void decrementContinuationDepth() {
            continuationDepth--;
        }

        public boolean isIndentedWithSpaces() {
            return linesWithSpaceIndents >= linesWithTabIndents;
        }

        public TabsAndIndentsStyle getTabsAndIndentsStyle() {
            boolean useTabs = !isIndentedWithSpaces();

            IndentStatistic indentFrequencies = useTabs ? tabIndentFrequencies : spaceIndentFrequencies;
            IndentStatistic continuationFrequencies = useTabs ? tabContinuationIndentFrequencies : spaceContinuationIndentFrequencies;

            int indent = indentFrequencies.commonIndent();
            int continuationIndent = continuationFrequencies.continuationIndent(indent);
            return new TabsAndIndentsStyle(
                    useTabs,
                    indent,
                    indent,
                    continuationIndent,
                    false,
                    new TabsAndIndentsStyle.MethodDeclarationParameters(
                            multilineAlignedToFirstArgument >= multilineNotAlignedToFirstArgument)
            );
        }
    }

    private static class FindLineFormatJavaVisitor extends JavaIsoVisitor<GeneralFormatStatistics> {
        @Override
        public Space visitSpace(Space space, Space.Location loc, GeneralFormatStatistics stats) {
            String prefix = space.getWhitespace();
            char[] chars = prefix.toCharArray();

            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (c == '\n' || c == '\r') {
                    if (c == '\n') {
                        if (i == 0 || chars[i - 1] != '\r') {
                            stats.linesWithLFNewLines++;
                        } else {
                            stats.linesWithCRLFNewLines++;
                        }
                    }
                }
            }
            return space;
        }
    }

    private static class FindIndentJavaVisitor extends JavaIsoVisitor<IndentStatistics> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, IndentStatistics stats) {
            // Only visit things we're interested in getting indentation from
            visitStatement(cd, stats);
            visit(cd.getBody(), stats);
            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, IndentStatistics stats) {
            if (method.getParameters().size() > 1) {
                int alignTo;
                if (method.getParameters().get(0).getPrefix().getLastWhitespace().contains("\n")) {
                    // Compare to the prefix of the first arg.
                    alignTo = method.getParameters().get(0).getPrefix().getLastWhitespace().length() - 1;
                } else {
                    String source = method.print(getCursor().getParentOrThrow());
                    alignTo = source.indexOf(method.getParameters().get(0).print(getCursor())) - 1;
                }
                List<Statement> parameters = method.getParameters();
                for (int i = 1; i < parameters.size(); i++) {
                    if (parameters.get(i).getPrefix().getLastWhitespace().contains("\n")) {
                        if (alignTo == parameters.get(i).getPrefix().getLastWhitespace().length()) {
                            stats.multilineAlignedToFirstArgument++;
                        } else {
                            stats.multilineNotAlignedToFirstArgument++;
                        }
                    }
                }
            }
            // Visit only those parts of the declaration we're interested in getting indentation info from
            visitStatement(method, stats);
            ListUtils.map(method.getLeadingAnnotations(), a -> visitAndCast(a, stats));
            visitContainer(method.getPadding().getParameters(), JContainer.Location.METHOD_DECLARATION_PARAMETERS, stats);
            visit(method.getBody(), stats);
            return method;
        }

        @Override
        public J.Block visitBlock(J.Block block, IndentStatistics stats) {
            stats.incrementDepth();
            for (Statement s : block.getStatements()) {
                if (s instanceof Expression) {
                    // Some statement types, like method invocations, are also expressions
                    // If an expression appears in a context where statements are expected, its indent is not a continuation
                    Set<Expression> statementExpressions = getCursor()
                                .getMessage("STATEMENT_EXPRESSION", Collections.newSetFromMap(
                                            new IdentityHashMap<>(block.getStatements().size())));
                    statementExpressions.add((Expression) s);
                    getCursor().putMessage("STATEMENT_EXPRESSION", statementExpressions);
                }
                visit(s, stats);
            }
            stats.decrementDepth();
            return block;
        }

        @Override
        public J.AnnotatedType visitAnnotatedType(J.AnnotatedType annotatedType, IndentStatistics indentStatistics) {
            return annotatedType;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, IndentStatistics stats) {
            visitStatement(vd, stats);
            ListUtils.map(vd.getPadding().getVariables(), t -> visitRightPadded(t, JRightPadded.Location.NAMED_VARIABLE, stats));
            return vd;
        }

        @Override
        public J.NewArray visitNewArray(J.NewArray na, IndentStatistics stats) {
            visitExpression(na, stats);
            return na;
        }

        @Override
        public Statement visitStatement(Statement statement, IndentStatistics stats) {
            countIndents(statement.getPrefix().getWhitespace(), false, stats);
            return statement;
        }

        @Override
        public Expression visitExpression(Expression expression, IndentStatistics stats) {
            super.visitExpression(expression, stats);
            Set<Expression> statementExpressions = getCursor().getNearestMessage("STATEMENT_EXPRESSION", emptySet());
            if(statementExpressions.contains(expression)) {
                return expression;
            }
            countIndents(expression.getPrefix().getWhitespace(), true, stats);

            return expression;
        }

        @SuppressWarnings("CommentedOutCode")
        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, IndentStatistics stats) {
            Set<Expression> statementExpressions = getCursor().getNearestMessage("STATEMENT_EXPRESSION", emptySet());
            if(statementExpressions.contains(m)) {
                visitStatement(m, stats);
            } else {
                visitExpression(m, stats);
            }
            if(m.getPadding().getSelect() != null) {
                countIndents(m.getPadding().getSelect().getAfter().getWhitespace(), true, stats);
            }
            visitContainer(m.getPadding().getTypeParameters(), JContainer.Location.TYPE_PARAMETERS, stats);

            /*
             * The treatment of continuations in method argument lists is particularly head-hurting.
             * Some IDEs will do things like indent arguments differently if the current invocation is another invocation's select
             * For now avoid this source of confusion and inconsistency.
             */
//            stats.incrementContinuationDepth();
//            visitContainer(m.getPadding().getArguments(), JContainer.Location.METHOD_INVOCATION_ARGUMENTS, stats);
//            stats.decrementContinuationDepth();
            return m;
        }

        @Override
        public <T> JRightPadded<T> visitRightPadded(@Nullable JRightPadded<T> right, JRightPadded.Location loc, IndentStatistics indentStatistics) {
            return super.visitRightPadded(right, loc, indentStatistics);
        }

        private void countIndents(String space, boolean isContinuation, IndentStatistics stats) {
            int ni = space.lastIndexOf('\n');
            int depth = stats.getDepth();
            if (ni >= 0 && depth > 0) {
                int spaceIndent = 0;
                int tabIndent = 0;
                boolean mixed = false;
                char[] chars = space.substring(ni).toCharArray();
                for (char c : chars) {
                    if (c == ' ') {
                        if (tabIndent > 0) {
                            mixed = true;
                        }
                        spaceIndent++;
                    } else if (c == '\t') {
                        if (spaceIndent > 0) {
                            mixed = true;
                        }
                        tabIndent++;
                    }
                }
                if (spaceIndent > 0 || tabIndent > 0) {
                    if (!mixed) {
                        if(isContinuation) {
                            stats.spaceContinuationIndentFrequencies.record(depth, stats.getContinuationDepth(), spaceIndent);
                            stats.tabContinuationIndentFrequencies.record(depth, stats.getContinuationDepth(), tabIndent);
                        } else {
                            stats.spaceIndentFrequencies.record(depth, 0, spaceIndent);
                            stats.tabIndentFrequencies.record(depth, 0, tabIndent);
                        }
                    }

                    if (spaceIndent > tabIndent) {
                        stats.linesWithSpaceIndents++;
                    } else {
                        stats.linesWithTabIndents++;
                    }
                }
            }
        }
    }

    private static class ImportLayoutStatistics {
        List<List<Block>> blocksPerSourceFile = new ArrayList<>();
        Map<String, String> pkgToBlockPattern = new LinkedHashMap<>();
        int staticAtTopCount = 0;
        int staticAtBotCount = 0;
        int javaBeforeJavaxCount = 0;
        int javaxBeforeJavaCount = 0;
        int minimumFoldedImports = Integer.MAX_VALUE;
        int minimumFoldedStaticImports = Integer.MAX_VALUE;

        public boolean isStaticImportsAtBot() {
            return staticAtBotCount >= staticAtTopCount;
        }

        public boolean isJavaxBeforeJava() {
            return javaxBeforeJavaCount >= javaBeforeJavaxCount;
        }

        enum BlockType {
            Import,
            ImportStatic
        }

        @SuppressWarnings("DuplicatedCode")
        public ImportLayoutStyle getImportLayoutStyle() {
            // the simplest heuristic is just to take the single longest block sequence and
            // assume that represents the most variation in the project
            return blocksPerSourceFile.stream()
                    .max(Comparator
                            .<List<Block>, Integer>comparing(List::size)
                            .thenComparing(blocks -> blocks.stream()
                                    .filter(b -> "all other imports".equals(b.pattern))
                                    .count()
                            )
                    )
                    .map(longestBlocks -> {
                        ImportLayoutStyle.Builder builder = ImportLayoutStyle.builder();
                        boolean insertAllOthers = false;
                        boolean insertStaticAllOthers = false;
                        boolean containsJava = false;
                        boolean containsJavax = false;

                        int insertAllOtherAtIndex = 0;
                        int insertStaticAllOtherAtIndex = 0;
                        int nonStaticMaxCount = Integer.MIN_VALUE;
                        int staticMaxCount = Integer.MIN_VALUE;
                        int nonStaticCountPos = 0;
                        int staticCountPos = 0;
                        int nonStaticPos = 0;
                        int staticPos = 0;

                        List<Block> nonStaticBlocks = new ArrayList<>(); // Isolate static imports to add at top or bottom of layout.
                        List<Block> staticBlocks = new ArrayList<>(); // Isolate static imports to add at top or bottom of layout.
                        List<Integer> countOfBlocksInNonStaticGroups = new ArrayList<>();
                        List<Integer> countOfBlocksInStaticGroups = new ArrayList<>();

                        for (Block block : longestBlocks) {
                            if (BlockType.ImportStatic.equals(block.type)) {
                                staticBlocks.add(block);
                                countOfBlocksInStaticGroups.add(0);
                                countOfBlocksInStaticGroups.set(staticCountPos, countOfBlocksInStaticGroups.get(staticCountPos) + 1);
                                if (staticMaxCount < countOfBlocksInStaticGroups.get(staticCountPos)) {
                                    staticMaxCount = countOfBlocksInStaticGroups.get(staticCountPos);
                                    insertStaticAllOtherAtIndex = staticCountPos;
                                    insertStaticAllOthers = true;
                                }

                                if (block.addBlankLine) {
                                    staticCountPos = staticPos + 1;
                                }
                                staticPos++;
                            } else {
                                if (!containsJava && "java.*".equals(block.pattern)) {
                                    containsJava = true;
                                }

                                if (!containsJavax && "javax.*".equals(block.pattern)) {
                                    containsJavax = true;
                                }

                                nonStaticBlocks.add(block);
                                countOfBlocksInNonStaticGroups.add(0);
                                countOfBlocksInNonStaticGroups.set(nonStaticCountPos, countOfBlocksInNonStaticGroups.get(nonStaticCountPos) + 1);
                                if (nonStaticMaxCount < countOfBlocksInNonStaticGroups.get(nonStaticCountPos)) {
                                    nonStaticMaxCount = countOfBlocksInNonStaticGroups.get(nonStaticCountPos);
                                    insertAllOtherAtIndex = nonStaticCountPos;
                                    insertAllOthers = true;
                                }

                                if (block.addBlankLine) {
                                    nonStaticCountPos = nonStaticPos + 1;
                                }
                                nonStaticPos++;
                            }
                        }

                        // Add static imports at the top if it's the standard.
                        boolean addNewLine = false;
                        if (!isStaticImportsAtBot()) {
                            // There are no static imports, add an all other import block.
                            if (!insertStaticAllOthers) {
                                builder = builder.importStaticAllOthers();
                            }

                            for (int i = 0; i < staticBlocks.size(); i++) {
                                // Insert the static all others block.
                                if (insertStaticAllOthers) {
                                    if (i == insertStaticAllOtherAtIndex) {
                                        builder = builder.importStaticAllOthers();
                                        addNewLine = true;
                                        continue;
                                    } else {
                                        if (countOfBlocksInStaticGroups.get(i) == 0 || i < insertStaticAllOtherAtIndex) {
                                            continue;
                                        } else {
                                            insertStaticAllOthers = false;
                                        }
                                    }
                                }

                                if (addNewLine) {
                                    builder = builder.blankLine();
                                    addNewLine = false;
                                }

                                Block block = staticBlocks.get(i);
                                builder.staticImportPackage(block.pattern);
                                if (block.addBlankLine && i != staticBlocks.size() - 1) {
                                    builder = builder.blankLine();
                                }
                            }
                        }
                        addNewLine = !isStaticImportsAtBot();

                        // There are no non-static imports, add a block of all other import.
                        if (!insertAllOthers) {
                            if (addNewLine) {
                                builder = builder.blankLine();
                            }

                            builder = builder.importAllOthers();
                            // Add java/javax if they're missing from the block that is being used as a template.
                            if (!containsJava && !containsJavax) {
                                builder = builder.blankLine();
                                if (isJavaxBeforeJava()) {
                                    builder = builder.importPackage("javax.*");
                                    builder = builder.importPackage("java.*");
                                } else {
                                    builder = builder.importPackage("java.*");
                                    builder = builder.importPackage("javax.*");
                                }
                            }
                            addNewLine = true;
                        }

                        boolean addJavaOrJavax = true; // Used to normalize the pos of java and javax imports.
                        for (int i = 0; i < nonStaticBlocks.size(); i++) {
                            if (insertAllOthers) {
                                // Insert the all others block.
                                if (i == insertAllOtherAtIndex) {
                                    if (addNewLine) {
                                        builder = builder.blankLine();
                                        addNewLine = false;
                                    }
                                    builder = builder.importAllOthers();
                                    // Add java/javax if they're missing from the block that is being used as a template.
                                    if (!containsJava && !containsJavax) {
                                        builder = builder.blankLine();
                                        if (isJavaxBeforeJava()) {
                                            builder = builder.importPackage("javax.*");
                                            builder = builder.importPackage("java.*");
                                        } else {
                                            builder = builder.importPackage("java.*");
                                            builder = builder.importPackage("javax.*");
                                        }
                                    }
                                    continue;
                                } else if (i > insertAllOtherAtIndex) {
                                    if (countOfBlocksInNonStaticGroups.get(i) == 0) {
                                        continue;
                                    } else {
                                        insertAllOthers = false;
                                        addNewLine = true;
                                    }
                                }
                            }

                            Block block = nonStaticBlocks.get(i);
                            if (addJavaOrJavax && "java.*".equals(block.pattern)) {
                                if (addNewLine) {
                                    builder = builder.blankLine();
                                    addNewLine = false;
                                }

                                if (!(i - 1 >= 0 && "javax.*".equals(nonStaticBlocks.get(i - 1).pattern) ||
                                        i + 1 < nonStaticBlocks.size() && "javax.*".equals(nonStaticBlocks.get(i + 1).pattern))) {
                                    if (isJavaxBeforeJava()) {
                                        builder = builder.importPackage("javax.*");
                                        builder = builder.importPackage("java.*");
                                    } else {
                                        builder = builder.importPackage("java.*");
                                        builder = builder.importPackage("javax.*");
                                    }
                                    addNewLine = true;
                                    addJavaOrJavax = false;
                                } else {
                                    builder = builder.importPackage(block.pattern);
                                }
                            } else if (addJavaOrJavax && "javax.*".equals(block.pattern)) {
                                if (addNewLine) {
                                    builder = builder.blankLine();
                                    addNewLine = false;
                                }

                                if (!(i - 1 >= 0 && "java.*".equals(nonStaticBlocks.get(i - 1).pattern) ||
                                        i + 1 < nonStaticBlocks.size() - 1 && "java.*".equals(nonStaticBlocks.get(i + 1).pattern))) {
                                    if (isJavaxBeforeJava()) {
                                        builder = builder.importPackage("javax.*");
                                        builder = builder.importPackage("java.*");
                                    } else {
                                        builder = builder.importPackage("java.*");
                                        builder = builder.importPackage("javax.*");
                                    }
                                    addNewLine = true;
                                    addJavaOrJavax = false;
                                } else {
                                    builder = builder.importPackage(block.pattern);
                                }
                            } else {
                                if (addNewLine) {
                                    builder = builder.blankLine();
                                    addNewLine = false;
                                }

                                builder = builder.importPackage(block.pattern);
                            }
                            if (block.addBlankLine && i != nonStaticBlocks.size() - 1) {
                                builder = builder.blankLine();
                            }
                        }

                        // Add statics at bottom.
                        if (isStaticImportsAtBot()) {
                            builder = builder.blankLine();
                            addNewLine = false;

                            // There are no static imports, add an all other import block.
                            if (!insertStaticAllOthers) {
                                builder = builder.importStaticAllOthers();
                            }

                            for (int i = 0; i < staticBlocks.size(); i++) {
                                // Insert the static all others block.
                                if (insertStaticAllOthers) {
                                    if (i == insertStaticAllOtherAtIndex) {
                                        builder = builder.importStaticAllOthers();
                                        continue;
                                    } else if (i > insertStaticAllOtherAtIndex) {
                                        if (countOfBlocksInStaticGroups.get(i) == 0) {
                                            continue;
                                        } else {
                                            insertStaticAllOthers = false;
                                            addNewLine = true;
                                        }
                                    }
                                }

                                Block block = staticBlocks.get(i);
                                if (addNewLine || i > 0 && staticBlocks.get(i - 1).addBlankLine) {
                                    builder = builder.blankLine();
                                    addNewLine = false;
                                }

                                builder = builder.staticImportPackage(block.pattern);
                            }
                        }

                        if (longestBlocks.isEmpty()) {
                            builder.importAllOthers();
                            builder.blankLine();
                            if (isJavaxBeforeJava()) {
                                builder = builder.importPackage("javax.*");
                                builder = builder.importPackage("java.*");
                            } else {
                                builder = builder.importPackage("java.*");
                                builder = builder.importPackage("javax.*");
                            }
                            builder.blankLine();
                            builder.importStaticAllOthers();
                        }

                        // set lower limits in case type attribution is really messed up on the project
                        // and we can't effectively count star imports
                        builder.classCountToUseStarImport(Math.max(minimumFoldedImports, 5));
                        builder.nameCountToUseStarImport(Math.max(minimumFoldedStaticImports, 3));

                        return builder.build();
                    })
                    .orElse(IntelliJ.importLayout());
        }

        @EqualsAndHashCode
        static class Block {
            private final BlockType type;
            private final String pattern;
            private final boolean addBlankLine;

            Block(BlockType type, String pattern, boolean addBlankLine) {
                this.type = type;
                this.pattern = pattern;
                this.addBlankLine = addBlankLine;
            }
        }

        /**
         * Maps the imported packages to patterns used to create Blocks in the ImportLayout.
         * Patterns are generated early to prevent block patterns that are too specific.
         * Ex. org.openrewrite.* vs. org.openrewrite.java.test.*
         */
        public void mapBlockPatterns(NavigableSet<String> importedPackages) {
            String longestCommonPrefix = null;
            String prevLCP = null;
            List<String> prevPackages = new ArrayList<>();

            for (String pkg : importedPackages) {
                longestCommonPrefix = longestCommonPrefix(pkg, longestCommonPrefix);
                if (!prevPackages.isEmpty() && longestCommonPrefix.chars().filter(c -> c == '.').count() <= 1 && !StringUtils.isNullOrEmpty(prevLCP)) {
                    for (String prev : prevPackages) {
                        if (prevLCP.startsWith("java.")) {
                            prevLCP = "java.";
                        } else if (prevLCP.startsWith("javax.")) {
                            prevLCP = "javax.";
                        }
                        this.pkgToBlockPattern.put(prev, prevLCP + "*");
                    }
                    longestCommonPrefix = pkg;
                    prevPackages.clear();
                }

                prevPackages.add(pkg);
                prevLCP = longestCommonPrefix;
            }

            for (String prev : prevPackages) {
                this.pkgToBlockPattern.put(prev, prevLCP + "*");
            }
        }

        private String longestCommonPrefix(String pkg, @Nullable String lcp) {
            if (lcp == null) {
                return pkg;
            }

            char[] p1 = pkg.toCharArray();
            char[] p2 = lcp.toCharArray();
            int i = 0;
            for (; i < p1.length && i < p2.length; i++) {
                if (p1[i] != p2[i]) {
                    break;
                }
            }
            return lcp.substring(0, i);
        }
    }

    private static class FindImportLayout extends JavaIsoVisitor<ImportLayoutStatistics> {
        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ImportLayoutStatistics importLayoutStatistics) {
            Set<ImportLayoutStatistics.Block> blocks = new LinkedHashSet<>();

            importLayoutStatistics.staticAtBotCount += (cu.getImports().size() > 0 &&
                    cu.getImports().get(cu.getImports().size() - 1).isStatic()) ? 1 : 0;
            importLayoutStatistics.staticAtTopCount += (cu.getImports().size() > 0 &&
                    cu.getImports().get(0).isStatic()) ? 1 : 0;

            boolean staticBlock = false;
            int blockStart = 0;
            int i = 0;
            String previousPkg = "";
            int previousPkgCount = 1;
            int javaPos = Integer.MAX_VALUE;
            int javaxPos = Integer.MAX_VALUE;
            Map<ImportLayoutStatistics.Block, Integer> referenceCount = new HashMap<>();
            for (J.Import anImport : cu.getImports()) {
                previousPkgCount += previousPkg.equals(importLayoutStatistics.pkgToBlockPattern.get(anImport.getPackageName() + ".")) ? 1 : 0;
                boolean containsNewLine = anImport.getPrefix().getWhitespace().contains("\n\n") || anImport.getPrefix().getWhitespace().contains("\r\n\r\n");
                if (containsNewLine ||
                        i > 0 && importLayoutStatistics.pkgToBlockPattern.containsKey(anImport.getPackageName() + ".") &&
                                !previousPkg.equals(importLayoutStatistics.pkgToBlockPattern.get(anImport.getPackageName() + "."))) {
                    if (i - blockStart > 0) {
                        ImportLayoutStatistics.Block block = new ImportLayoutStatistics.Block(
                                staticBlock ?
                                        ImportLayoutStatistics.BlockType.ImportStatic :
                                        ImportLayoutStatistics.BlockType.Import,
                                previousPkg,
                                containsNewLine);

                        javaPos = "java.*".equals(block.pattern) && javaPos > blockStart ? blockStart : javaPos;
                        javaxPos = "javax.*".equals(block.pattern) && javaxPos > blockStart ? blockStart : javaxPos;

                        if (blocks.contains(block) && previousPkgCount > referenceCount.get(block)) {
                            blocks.remove(block);
                        }
                        blocks.add(block);
                        referenceCount.put(block, previousPkgCount + 1);
                        previousPkgCount = 1;
                    }

                    blockStart = i;
                }

                if (anImport.getQualid().getSimpleName().equals("*")) {
                    if (anImport.isStatic()) {
                        int count = 0;
                        for (JavaType.Variable variable : cu.getTypesInUse().getVariables()) {
                            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(variable.getType());
                            if (fq != null && anImport.getTypeName().equals(fq.getFullyQualifiedName())) {
                                count++;
                            }
                        }

                        importLayoutStatistics.minimumFoldedStaticImports = Math.min(
                                importLayoutStatistics.minimumFoldedStaticImports,
                                count
                        );
                    } else {
                        Set<String> fqns = new HashSet<>();
                        for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
                            if (type instanceof JavaType.FullyQualified) {
                                JavaType.FullyQualified fq = (JavaType.FullyQualified) type;
                                if (anImport.getPackageName().equals(fq.getPackageName())) {
                                    // don't count directly, as JavaType.Parameterized can
                                    // CONTAIN a FullyQualified that matches a raw FullyQualified
                                    fqns.add(fq.getFullyQualifiedName());
                                }
                            }
                        }

                        importLayoutStatistics.minimumFoldedImports = Math.min(
                                importLayoutStatistics.minimumFoldedImports,
                                fqns.size()
                        );
                    }
                }

                staticBlock = anImport.isStatic();
                i++;
                previousPkg = importLayoutStatistics.pkgToBlockPattern.getOrDefault(anImport.getPackageName() + ".", "");
            }

            if (i - blockStart > 0) {
                ImportLayoutStatistics.Block block = new ImportLayoutStatistics.Block(
                        staticBlock ?
                                ImportLayoutStatistics.BlockType.ImportStatic :
                                ImportLayoutStatistics.BlockType.Import,
                        previousPkg,
                        false);

                if (blocks.contains(block) && previousPkgCount > referenceCount.get(block)) {
                    blocks.remove(block);
                }

                javaPos = "java.*".equals(block.pattern) ? blockStart : javaPos;
                javaxPos = "javax.*".equals(block.pattern) ? blockStart : javaxPos;
                blocks.add(block);
            }

            if (javaPos != Integer.MAX_VALUE && javaxPos != Integer.MAX_VALUE) {
                importLayoutStatistics.javaBeforeJavaxCount += javaPos < javaxPos ? 1 : 0;
                importLayoutStatistics.javaxBeforeJavaCount += javaxPos < javaPos ? 1 : 0;
            }

            importLayoutStatistics.blocksPerSourceFile.add(new ArrayList<>(blocks));

            return cu;
        }
    }

    private static class SpacesStatistics {
        int beforeIf = 1;
        int beforeMethodCall = 0;
        int beforeMethodDeclaration = 0;
        int beforeFor = 1;
        int beforeWhile = 1;
        int beforeSwitch = 1;
        int beforeTry = 1;
        int beforeCatch = 1;
        int beforeSynchronized = 1;
        int beforeComma = 0;
        int afterComma = 1;
        int beforeColonInForEach = 1;
        int beforeForSemiColon = 0;
        int afterForSemiColon = 0;
        int afterTypeCast = 0;
        int withinMethodCallParentheses = 0;

        public SpacesStyle getSpacesStyle() {
            SpacesStyle spaces = IntelliJ.spaces();
            return spaces
                    .withBeforeParentheses(
                            new SpacesStyle.BeforeParentheses(
                                    beforeMethodDeclaration > 0,
                                    beforeMethodCall > 0,
                                    beforeIf > 0,
                                    beforeFor > 0 || beforeWhile > 0,
                                    beforeWhile > 0 || beforeFor > 0,
                                    beforeSwitch > 0,
                                    beforeTry > 0 || beforeCatch > 0,
                                    beforeTry > 0 || beforeCatch > 0,
                                    beforeSynchronized > 0,
                                    false
                            )
                    )
                    .withWithin(new SpacesStyle.Within(
                            spaces.getWithin().getCodeBraces(),
                            spaces.getWithin().getBrackets(),
                            spaces.getWithin().getArrayInitializerBraces(),
                            spaces.getWithin().getEmptyArrayInitializerBraces(),
                            spaces.getWithin().getGroupingParentheses(),
                            spaces.getWithin().getMethodDeclarationParentheses(),
                            spaces.getWithin().getEmptyMethodDeclarationParentheses(),
                            withinMethodCallParentheses > 0,
                            spaces.getWithin().getEmptyMethodCallParentheses(),
                            spaces.getWithin().getIfParentheses(),
                            spaces.getWithin().getForParentheses(),
                            spaces.getWithin().getWhileParentheses(),
                            spaces.getWithin().getSwitchParentheses(),
                            spaces.getWithin().getTryParentheses(),
                            spaces.getWithin().getCatchParentheses(),
                            spaces.getWithin().getSynchronizedParentheses(),
                            spaces.getWithin().getTypeCastParentheses(),
                            spaces.getWithin().getAnnotationParentheses(),
                            spaces.getWithin().getAngleBrackets(),
                            spaces.getWithin().getRecordHeader()
                    ))
                    .withOther(new SpacesStyle.Other(
                            beforeComma > 0,
                            afterComma >= 1,
                            beforeForSemiColon > 0,
                            afterForSemiColon >= 0,
                            afterTypeCast > 0,
                            beforeColonInForEach > 0,
                            false
                    ));
        }
    }

    private static class FindSpacesStyle extends JavaIsoVisitor<SpacesStatistics> {
        @Override
        public J.TypeCast visitTypeCast(J.TypeCast typeCast, SpacesStatistics stats) {
            stats.afterTypeCast += hasSpace(typeCast.getExpression().getPrefix());
            return super.visitTypeCast(typeCast, stats);
        }

        @Override
        public J.Try.Catch visitCatch(J.Try.Catch _catch, SpacesStatistics stats) {
            stats.beforeCatch += hasSpace(_catch.getParameter().getPrefix());
            return super.visitCatch(_catch, stats);
        }

        @Override
        public J.DoWhileLoop visitDoWhileLoop(J.DoWhileLoop doWhileLoop, SpacesStatistics stats) {
            stats.beforeWhile += hasSpace(doWhileLoop.getWhileCondition().getPrefix());
            return super.visitDoWhileLoop(doWhileLoop, stats);
        }

        @Override
        public J.ForEachLoop visitForEachLoop(J.ForEachLoop forLoop, SpacesStatistics stats) {
            stats.beforeFor += hasSpace(forLoop.getControl().getPrefix());
            stats.beforeColonInForEach += hasSpace(forLoop.getControl().getPadding().getVariable().getAfter());
            return super.visitForEachLoop(forLoop, stats);
        }

        @Override
        public J.ForLoop visitForLoop(J.ForLoop forLoop, SpacesStatistics stats) {
            stats.beforeFor += hasSpace(forLoop.getControl().getPrefix());
            stats.beforeForSemiColon += hasSpace(forLoop.getControl().getPadding().getInit().get(forLoop.getControl().getInit().size() - 1).getAfter());
            stats.beforeForSemiColon += hasSpace(forLoop.getControl().getPadding().getCondition().getAfter());
            stats.afterForSemiColon += hasSpace(forLoop.getControl().getInit().get(forLoop.getControl().getInit().size() - 1).getPrefix());
            stats.afterForSemiColon += hasSpace(forLoop.getControl().getCondition().getPrefix());
            return super.visitForLoop(forLoop, stats);
        }

        @Override
        public J.If visitIf(J.If iff, SpacesStatistics stats) {
            stats.beforeIf += hasSpace(iff.getIfCondition().getPrefix());
            return super.visitIf(iff, stats);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, SpacesStatistics stats) {
            stats.beforeMethodDeclaration += hasSpace(method.getPadding().getParameters().getBefore());
            return super.visitMethodDeclaration(method, stats);
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, SpacesStatistics stats) {
            stats.beforeMethodCall += hasSpace(method.getPadding().getArguments().getBefore());

            if (method.getArguments().size() > 1) {
                stats.withinMethodCallParentheses += hasSpace(method.getPadding().getArguments().getPadding().getElements().get(0).getElement().getPrefix());
                stats.withinMethodCallParentheses += hasSpace(method.getPadding().getArguments().getPadding().getElements().get(method.getArguments().size() - 1).getAfter());
                for (JRightPadded<Expression> elem : method.getPadding().getArguments().getPadding().getElements()) {
                    stats.beforeComma += hasSpace(elem.getAfter());
                }
                for (Expression e : method.getArguments()) {
                    stats.afterComma += hasSpace(e.getPrefix());
                }
            }
            return super.visitMethodInvocation(method, stats);
        }

        @Override
        public J.NewArray visitNewArray(J.NewArray newArray, SpacesStatistics stats) {
            JContainer<Expression> initializer = newArray.getPadding().getInitializer();
            if (newArray.getInitializer() != null && initializer != null && initializer.getElements().size() > 0) {
                for (JRightPadded<Expression> elem : initializer.getPadding().getElements()) {
                    stats.beforeComma += hasSpace(elem.getAfter());
                }
                for (Expression e : newArray.getInitializer()) {
                    stats.afterComma += hasSpace(e.getPrefix());
                }
            }
            return super.visitNewArray(newArray, stats);
        }

        @Override
        public J.Switch visitSwitch(J.Switch _switch, SpacesStatistics stats) {
            stats.beforeSwitch += hasSpace(_switch.getSelector().getPrefix());
            return super.visitSwitch(_switch, stats);
        }

        @Override
        public J.Synchronized visitSynchronized(J.Synchronized _sync, SpacesStatistics stats) {
            stats.beforeSynchronized += hasSpace(_sync.getLock().getPrefix());
            return super.visitSynchronized(_sync, stats);
        }

        @Override
        public J.Try visitTry(J.Try _try, SpacesStatistics stats) {
            if (_try.getPadding().getResources() != null) {
                stats.beforeTry += hasSpace(_try.getPadding().getResources().getBefore());
            }
            return super.visitTry(_try, stats);
        }

        @Override
        public J.WhileLoop visitWhileLoop(J.WhileLoop whileLoop, SpacesStatistics stats) {
            stats.beforeWhile += hasSpace(whileLoop.getCondition().getPrefix());
            return super.visitWhileLoop(whileLoop, stats);
        }

        private int hasSpace(Space space) {
            return space.getWhitespace().contains(" ") ? 1 : -1;
        }
    }

    private static class WrappingAndBracesStatistics {
        int elseOnNewLine = 0;

        public WrappingAndBracesStyle getWrappingAndBracesStyle() {
            WrappingAndBracesStyle wrappingAndBracesStyle = IntelliJ.wrappingAndBraces();
            return wrappingAndBracesStyle
                    .withIfStatement(new WrappingAndBracesStyle.IfStatement(
                            elseOnNewLine > 0)
                    );
        }
    }

    private static class FindWrappingAndBracesStyle extends JavaIsoVisitor<WrappingAndBracesStatistics> {
        @Override
        public J.If.Else visitElse(J.If.Else elze, WrappingAndBracesStatistics stats) {
            stats.elseOnNewLine += hasNewLine(elze.getPrefix());
            return super.visitElse(elze, stats);
        }

        private int hasNewLine(Space space) {
            return space.getWhitespace().contains("\n") ? 1 : -1;
        }
    }
}
