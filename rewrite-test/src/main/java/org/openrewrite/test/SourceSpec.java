/*
 * Copyright 2022 the original author or authors.
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
package org.openrewrite.test;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ThrowingConsumer;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.Marker;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;

@RequiredArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
public class SourceSpec<T extends SourceFile> implements SourceSpecs {
    @EqualsAndHashCode.Include
    final UUID id = UUID.randomUUID();

    final Class<T> sourceFileType;

    @Nullable
    final String dsl;

    final Parser.Builder parser;

    @Nullable
    final String before;

    @Nullable
    UnaryOperator<String> after;

    /**
     * Apply a function to each SourceFile after recipe execution.
     * Useful for validating the AST or its metadata.
     */
    final EachResult eachResult;

    public interface EachResult {
        EachResult noop = (sourceFile, testMethodSpec, testClassSpec) -> sourceFile;

        SourceFile accept(SourceFile sourceFile, RecipeSpec testMethodSpec, RecipeSpec testClassSpec);
    }

    final ThrowingConsumer<ExecutionContext> customizeExecutionContext;

    public SourceSpec(Class<T> sourceFileType, @Nullable String dsl,
                      Parser.Builder parser, @Nullable String before, @Nullable UnaryOperator<String> after) {
        this.sourceFileType = sourceFileType;
        this.dsl = dsl;
        this.parser = parser;
        this.before = before;
        this.after = after;
        this.eachResult = EachResult.noop;
        this.customizeExecutionContext = (ctx) -> {
        };
    }

    @Setter
    @Nullable
    protected String sourceSetName;

    protected Path dir = Paths.get("");

    @Nullable
    protected Path sourcePath;

    protected final List<Marker> markers = new ArrayList<>();

    @Nullable
    Path getSourcePath() {
        return sourcePath == null ? null : dir.resolve(sourcePath);
    }

    protected ThrowingConsumer<T> beforeRecipe = t -> {

    };

    protected ThrowingConsumer<T> afterRecipe = t -> {
    };

    protected boolean skip = false;

    protected boolean noTrim = false;

    public SourceSpec<T> path(Path sourcePath) {
        this.sourcePath = sourcePath;
        return this;
    }

    public SourceSpec<T> path(String sourcePath) {
        this.sourcePath = Paths.get(sourcePath);
        return this;
    }

    public SourceSpec<T> markers(Marker... markers) {
        Collections.addAll(this.markers, markers);
        return this;
    }

    /**
     * Apply a function to specify what the after text of a recipe run should be.
     *
     * @param after A unary operator that takes the actual result and returns the expected result.
     *              The actual result can be used to pull out things that are dynamic, like timestamps or
     *              dependency versions that may change between runs.
     * @return This source spec.
     */
    public SourceSpec<T> after(UnaryOperator<String> after) {
        this.after = after;
        return this;
    }

    public SourceSpec<T> beforeRecipe(ThrowingConsumer<T> beforeRecipe) {
        this.beforeRecipe = beforeRecipe;
        return this;
    }

    public SourceSpec<T> afterRecipe(ThrowingConsumer<T> afterRecipe) {
        this.afterRecipe = afterRecipe;
        return this;
    }

    public SourceSpec<T> skip() {
        return skip(true);
    }

    public SourceSpec<T> skip(@Nullable Boolean skip) {
        this.skip = Boolean.TRUE.equals(skip);
        return this;
    }

    public SourceSpec<T> noTrim() {
        return noTrim(true);
    }

    public SourceSpec<T> noTrim(@Nullable Boolean noTrim) {
        this.noTrim = Boolean.TRUE.equals(noTrim);
        return this;
    }

    @Override
    public Iterator<SourceSpec<?>> iterator() {
        return new Iterator<SourceSpec<?>>() {
            boolean next = true;

            @Override
            public boolean hasNext() {
                return next;
            }

            @Override
            public SourceSpec<?> next() {
                next = false;
                return SourceSpec.this;
            }
        };
    }
}
