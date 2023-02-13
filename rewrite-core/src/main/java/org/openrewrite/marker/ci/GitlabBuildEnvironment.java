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
package org.openrewrite.marker.ci;

import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.With;

import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.openrewrite.Tree.randomId;
import static org.openrewrite.marker.OsProvenance.hostname;

@Value
@EqualsAndHashCode(callSuper = false)
public class GitlabBuildEnvironment implements BuildEnvironment {
    @With
    UUID id;

    String buildId;
    String buildUrl;
    String host;
    String job;

    public static GitlabBuildEnvironment build(UnaryOperator<String> environment) {
        return new GitlabBuildEnvironment(
                randomId(),
                environment.apply("CI_BUILD_ID"),
                environment.apply("CI_JOB_URL"),
                hostname(),
                environment.apply("CI_BUILD_NAME")
        );
    }
}
