/*
 * Copyright 2020 the original author or authors.
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
package org.openrewrite.java;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.search.DeclaresMethod;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.*;

@Value
@EqualsAndHashCode(callSuper = true)
public class ChangeMethodName extends Recipe {

    @Option(displayName = "Method pattern",
            description = "A method pattern that is used to find matching method declarations/invocations.",
            example = "org.mockito.Matchers anyVararg()")
    String methodPattern;

    @Option(displayName = "New method name",
            description = "The method name that will replace the existing name.",
            example = "any")
    String newMethodName;

    @Option(displayName = "Match on overrides",
            description = "When enabled, find methods that are overrides of the method pattern.",
            required = false)
    @Nullable
    Boolean matchOverrides;

    @Option(displayName = "Ignore type definition",
            description = "When set to `true` the definition of the old type will be left untouched. " +
                    "This is useful when you're replacing usage of a class but don't want to rename it.",
            example = "true",
            required = false)
    @Nullable
    Boolean ignoreDefinition;

    @Override
    public String getDisplayName() {
        return "Change method name";
    }

    @Override
    public String getDescription() {
        return "Rename a method.";
    }

    @Override
    protected JavaVisitor<ExecutionContext> getSingleSourceApplicableTest() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public JavaSourceFile visitJavaSourceFile(JavaSourceFile cu, ExecutionContext executionContext) {
                if(Boolean.TRUE.equals(ignoreDefinition)) {
                    J j = new DeclaresMethod<>(methodPattern, matchOverrides).visitNonNull(cu, executionContext);
                    if(cu != j) {
                        return cu;
                    }
                }
                doAfterVisit(new UsesMethod<>(methodPattern, matchOverrides));
                doAfterVisit(new DeclaresMethod<>(methodPattern, matchOverrides));
                return cu;
            }
        };
    }

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    @Override
    public JavaVisitor<ExecutionContext> getVisitor() {
        return new ChangeMethodNameVisitor(new MethodMatcher(methodPattern, matchOverrides));
    }

    private class ChangeMethodNameVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final MethodMatcher methodMatcher;

        private ChangeMethodNameVisitor(MethodMatcher methodMatcher) {
            this.methodMatcher = methodMatcher;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
            J.ClassDeclaration classDecl = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if(classDecl == null) {
                return m;
            }
            if (methodMatcher.matches(method, classDecl)) {
                JavaType.Method type = m.getMethodType();
                if (type != null) {
                    type = type.withName(newMethodName);
                }
                m = m.withName(m.getName().withSimpleName(newMethodName))
                        .withMethodType(type);
            }
            return m;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            if (methodMatcher.matches(method, true) && !method.getSimpleName().equals(newMethodName)) {
                JavaType.Method type = m.getMethodType();
                if (type != null) {
                    type = type.withName(newMethodName);
                }
                m = m.withName(m.getName().withSimpleName(newMethodName))
                        .withMethodType(type);
            }
            return m;
        }

        @Override
        public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext context) {
            J.MemberReference m = super.visitMemberReference(memberRef, context);
            if (methodMatcher.matches(m.getMethodType()) && !m.getReference().getSimpleName().equals(newMethodName)) {
                JavaType.Method type = m.getMethodType();
                if (type != null) {
                    type = type.withName(newMethodName);
                }
                m = m.withReference(m.getReference().withSimpleName(newMethodName)).withMethodType(type);
            }
            return m;
        }

        /**
         * The only time field access should be relevant to changing method names is static imports.
         * This exists to turn
         * import static com.abc.B.static1;
         * into
         * import static com.abc.B.static2;
         */
        @Override
        public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
            J.FieldAccess f = super.visitFieldAccess(fieldAccess, ctx);
            if (methodMatcher.isFullyQualifiedClassReference(f)) {
                Expression target = f.getTarget();
                if (target instanceof J.FieldAccess) {
                    String className = target.printTrimmed(getCursor());
                    String fullyQualified = className + "." + newMethodName;
                    return TypeTree.build(fullyQualified)
                            .withPrefix(f.getPrefix());
                }
            }
            return f;
        }
    }
}
