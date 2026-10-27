/*
 * Copyright 2026 The agentflow Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.agentflow.starter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主源码的标准输出卫生守卫。
 *
 * <p>工程标准加固时已把 {@code System.out} / {@code System.err} / {@code printStackTrace} 一类直写 标准输出的写法全部换成
 * SLF4J，现在五个模块的 {@code src/main/java} 里一处不剩。这个测试把该结论 固化成构建期判据：一旦有人重新引入直写 stdout / stderr
 * 的代码，构建立刻变红，而不是靠人去回忆 「这件事我们做过」。
 *
 * <p>两条自捡用例是守卫的一部分。正则只被放宽、不被收紧时，扫描用例会永远绿、什么也守不住； 有了自捡，正则被改坏会在同一轮构建里先红。
 */
class StdoutHygieneTest {

    /** 参与扫描的五个模块，扫描范围是各自的 {@code src/main/java}。 */
    private static final List<String> MODULES =
            List.of(
                    "agentflow-contract",
                    "agentflow-store",
                    "agentflow-core",
                    "agentflow-starter",
                    "agentflow-sample");

    /**
     * 禁用模式，逐条罗列而不用 {@code System\.} 兜底。
     *
     * <p>兜底会把 {@code System.nanoTime()}、{@code currentTimeMillis()}、{@code exit()}、 {@code
     * getProperty()}、{@code getenv()}、{@code lineSeparator()} 这些合法调用误报成违规， 误报一多守卫就会被当成噪音绕过。
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS =
            List.of(
                    Pattern.compile("System\\s*\\.\\s*out\\b"),
                    Pattern.compile("System\\s*\\.\\s*err\\b"),
                    Pattern.compile("System\\s*\\.\\s*console\\b"),
                    Pattern.compile("System\\s*\\.\\s*setOut\\b"),
                    Pattern.compile("System\\s*\\.\\s*setErr\\b"),
                    Pattern.compile("printStackTrace\\s*\\("),
                    Pattern.compile("FileDescriptor\\s*\\.\\s*(out|err)\\b"));

    /** 与 {@link #FORBIDDEN_PATTERNS} 逐位对应的违例样例，用来证明每条正则都还咬得住自己的目标。 */
    private static final List<String> FORBIDDEN_SAMPLES =
            List.of(
                    "System.out.println(\"x\");",
                    "System.err.println(\"x\");",
                    "System.console().printf(\"x\");",
                    "System.setOut(null);",
                    "System.setErr(null);",
                    "e.printStackTrace();",
                    "FileDescriptor.out.sync();");

    /** 合法代码样例：一条都不许被任何禁用模式命中。 */
    private static final List<String> ALLOWED_SAMPLES =
            List.of(
                    "log.info(\"a={}\", b);",
                    "long t0 = System.nanoTime();",
                    "long now = System.currentTimeMillis();",
                    "System.exit(1);",
                    "System.getProperty(\"os.name\");",
                    "System.getenv(\"HOME\");",
                    "String nl = System.lineSeparator();");

    /** 覆盖度闸：现状 42 个 .java，留 4 个余量，防止扫描路径改坏后「扫不到就等于没问题」。 */
    private static final int MIN_TOTAL_FILES = 38;

    /**
     * 定位仓库根：从当前工作目录逐级向上，取第一个同时存在三个已知模块源码目录的目录。
     *
     * <p>不用 {@code .git} 当标志，是因为构建会发生在删掉版本目录的副本上，依赖 {@code .git} 会让守卫在唯一真正跑构建的地方必然失败。
     */
    private static Path repoRoot() {
        List<String> markers =
                List.of(
                        "agentflow-contract/src/main/java",
                        "agentflow-store/src/main/java",
                        "agentflow-core/src/main/java");
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            boolean allPresent = true;
            for (String marker : markers) {
                if (!Files.isDirectory(current.resolve(marker))) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根：向上遍历未找到同时含 " + markers + " 的目录");
    }

    /**
     * 收集一个模块 {@code src/main/java} 下的 .java 文件，跳过构建产物与版本目录。
     *
     * @param root 仓库根
     * @param module 模块目录名
     * @return 排序后的文件列表
     * @throws IOException 目录不可读时抛出
     */
    private static List<Path> javaFiles(Path root, String module) throws IOException {
        Path base = root.resolve(module).resolve("src").resolve("main").resolve("java");
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !isExcluded(root, path))
                    .sorted()
                    .toList();
        }
    }

    /** 路径先统一分隔符再排除，避免在 Windows 上因反斜杠漏判。 */
    private static boolean isExcluded(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return relative.contains("/target/")
                || relative.contains(".git/")
                || relative.contains(".mvn/");
    }

    /**
     * 逐行匹配禁用模式。
     *
     * @param root 仓库根
     * @param file 待扫文件
     * @return 「相对路径:行号: 原行内容」形式的违例列表
     * @throws IOException 文件不可读时抛出
     */
    private static List<String> scanFile(Path root, Path file) throws IOException {
        // Windows 默认编码是 GBK，中文注释会被读成乱码，这里显式指定 UTF-8。
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = content.split("\r?\n", -1);
        String relative = root.relativize(file).toString().replace('\\', '/');
        List<String> violations = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            for (Pattern pattern : FORBIDDEN_PATTERNS) {
                if (pattern.matcher(lines[index]).find()) {
                    violations.add(relative + ":" + (index + 1) + ": " + lines[index].trim());
                    break;
                }
            }
        }
        return violations;
    }

    /** 每条禁用模式都必须命中自己对应的违例样例，否则说明正则被改坏、守卫已经失效。 */
    @Test
    void forbiddenPatternsMatchTheirSamples() {
        assertThat(FORBIDDEN_SAMPLES).as("违例样例与禁用模式必须逐位对应").hasSize(FORBIDDEN_PATTERNS.size());

        for (int index = 0; index < FORBIDDEN_PATTERNS.size(); index++) {
            Pattern pattern = FORBIDDEN_PATTERNS.get(index);
            String sample = FORBIDDEN_SAMPLES.get(index);
            assertThat(pattern.matcher(sample).find())
                    .as("第 %d 条禁用模式 %s 应命中违例样例 %s", index, pattern.pattern(), sample)
                    .isTrue();
        }
    }

    /** 合法代码一条都不许被命中，否则守卫会持续误报、最终被人绕开。 */
    @Test
    void forbiddenPatternsDoNotMatchAllowedCode() {
        for (String sample : ALLOWED_SAMPLES) {
            for (Pattern pattern : FORBIDDEN_PATTERNS) {
                assertThat(pattern.matcher(sample).find())
                        .as("禁用模式 %s 不应命中合法代码 %s", pattern.pattern(), sample)
                        .isFalse();
            }
        }
    }

    /** 五个模块的主源码里不得出现任何直写标准输出/标准错误的写法，同时校验扫描覆盖度。 */
    @Test
    void mainSourcesContainNoForbiddenPattern() throws IOException {
        Path root = repoRoot();
        Map<String, Integer> fileCountByModule = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();

        for (String module : MODULES) {
            List<Path> files = javaFiles(root, module);
            fileCountByModule.put(module, files.size());
            for (Path file : files) {
                violations.addAll(scanFile(root, file));
            }
        }
        int total = fileCountByModule.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(fileCountByModule)
                .as("每个模块都必须扫到 .java 文件，为 0 说明扫描路径写错了，守卫等于没跑")
                .allSatisfy((module, count) -> assertThat(count).isPositive());

        assertThat(total)
                .as(
                        "扫到的 .java 总数 %d 低于下限 %d，各模块实际文件数：%s",
                        total, MIN_TOTAL_FILES, fileCountByModule)
                .isGreaterThanOrEqualTo(MIN_TOTAL_FILES);

        assertThat(violations)
                .as(
                        "主源码不得直写标准输出/标准错误。违例（相对路径:行号: 原行）：\n%s\n各模块扫描文件数：%s",
                        String.join("\n", violations), fileCountByModule)
                .isEmpty();
    }
}
