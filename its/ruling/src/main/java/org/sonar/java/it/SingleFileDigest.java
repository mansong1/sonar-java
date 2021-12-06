/*
 * SonarQube Java
 * Copyright (C) 2013-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.it;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SingleFileDigest {
  private static final Logger LOGGER = Logger.getLogger(SingleFileDigest.class.getName());
  private static final Pattern RULE_KEY_PATTERN = Pattern.compile("[^\\-]+-(\\S+)\\.json", Pattern.CASE_INSENSITIVE);

  private final Path sourceDirectory;
  private final Path actualDirectory;

  public SingleFileDigest(Path sourceDirectory, Path actualDirectory) {
    this.sourceDirectory = sourceDirectory;
    this.actualDirectory = actualDirectory;
  }

  public Path report() throws IOException {
    if (!Files.isDirectory(this.sourceDirectory)) {
      throw new IllegalStateException(String.format("%s is not a valid source directory", this.sourceDirectory));
    }
    if (!Files.isDirectory(this.actualDirectory)) {
      throw new IllegalStateException(String.format("%s is not a valid actual directory", this.actualDirectory));
    }
    Set<Issue> issues = new TreeSet<>();
    Map<Path, List<Path>> jsonsPerProject = getJsonsPerProject(actualDirectory);
    for (Map.Entry<Path, List<Path>> project : jsonsPerProject.entrySet()) {
      for (Path json : project.getValue()) {
        Path other = sourceDirectory.resolve(actualDirectory.relativize(json));
        final Set<Issue> found = parse(json);
        final Set<Issue> expected = parse(other);
        Set<Issue> falseNegatives = new HashSet<>(expected);
        falseNegatives.removeAll(found);
        Set<Issue> falsePositives = new HashSet<>(found);
        falsePositives.removeAll(expected);
        falsePositives.forEach(fp -> fp.setStatus(Issue.STATUS.FALSE_POSITIVE));
        falseNegatives.forEach(fn -> fn.setStatus(Issue.STATUS.FALSE_NEGATIVE));
        issues.addAll(falseNegatives);
        issues.addAll(falsePositives);
      }
    }

    Path reportPath = this.actualDirectory.resolve("report.csv");
    Files.createFile(reportPath);
    try (PrintWriter writer = new PrintWriter(reportPath.toFile(), StandardCharsets.UTF_8)) {
      for (Issue issue : issues) {
        writer.println(issue.toCSV());
      }
    }
    return reportPath;
  }

  private static Map<Path, List<Path>> getJsonsPerProject(Path directory) throws IOException {
    Map<Path, List<Path>> jsonsPerProject = new TreeMap<>();
    try (Stream<Path> paths = Files.walk(directory)) {
      final List<Path> projects = paths.filter(path -> !directory.equals(path) && Files.isDirectory(path)).collect(Collectors.toList());
      for (Path project : projects) {
        try (Stream<Path> subPaths = Files.walk(project)) {
          List<Path> jsons = subPaths.filter(Files::isRegularFile).collect(Collectors.toList());
          jsonsPerProject.put(project, jsons);
        }
      }
    }
    return jsonsPerProject;
  }

  private static Set<Issue> parse(Path path) throws IOException {
    if (!Files.exists(path)) {
      return new TreeSet<>();
    }
    String ruleKey = extractRuleKey(path.getFileName().toString());
    String project = path.getParent().getFileName().toString();
    String content;
    try (FileInputStream fis = new FileInputStream(path.toFile())) {
      content = new String(fis.readAllBytes(), StandardCharsets.UTF_8);
    }
    Set<Issue> issues = new TreeSet<>();
    int index = 0;
    while (index < content.length() && content.charAt(index) != '{') {
      index++;
    }
    while (index < content.length()) {
      while (index < content.length() && content.charAt(index) != '\'') {
        index++;
      }
      index++;
      int startPath = index;
      while (index < content.length() && content.charAt(index) != '\'') {
        index++;
      }
      if (index >= content.length()) {
        break;
      }
      String sourcePath = content.substring(startPath, index);
      while (index < content.length() && content.charAt(index) != '[') {
        index++;
      }
      index++;
      int startArray = index;
      while (index < content.length() && content.charAt(index) != ']') {
        index++;
      }
      int endArray = index - 1;
      Arrays.stream(content.substring(startArray, endArray).split(",")).forEach(lineToken -> {
        int line = Integer.parseInt(lineToken.trim());
        Issue issue = new Issue(project, ruleKey, sourcePath, line);
        issues.add(issue);
      });
    }
    return issues;
  }

  private static String extractRuleKey(String text) {
    final Matcher matcher = RULE_KEY_PATTERN.matcher(text);
    if (!matcher.matches()) {
      return text;
    }
    return matcher.group(1);
  }

  static class Issue implements Comparable<Issue> {

    public enum STATUS {
      SIMILAR, FALSE_NEGATIVE, FALSE_POSITIVE
    }

    private final String project;
    private final String ruleKey;
    private final String path;
    private final int line;
    private STATUS status;

    public Issue(String project, String ruleKey, String path, int line) {
      this.project = project;
      this.ruleKey = ruleKey;
      this.path = path;
      this.line = line;
    }

    public void setStatus(STATUS status) {
      this.status = status;
    }

    @Override
    public int compareTo(SingleFileDigest.Issue other) {
      int difference = this.project.compareTo(other.project);
      if (difference != 0) {
        return difference;
      }
      difference = this.ruleKey.compareTo(other.ruleKey);
      if (difference != 0) {
        return difference;
      }
      difference = this.path.compareTo(other.path);
      if (difference != 0) {
        return difference;
      }
      difference = this.line - other.line;
      return difference;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Issue)) return false;
      Issue issue = (Issue) o;
      return line == issue.line && Objects.equals(project, issue.project) && Objects.equals(ruleKey, issue.ruleKey) && Objects.equals(path, issue.path) && status == issue.status;
    }

    @Override
    public int hashCode() {
      int result = project != null ? project.hashCode() : 0;
      result = 31 * result + (ruleKey != null ? ruleKey.hashCode() : 0);
      result = 31 * result + (path != null ? path.hashCode() : 0);
      result = 31 * result + line;
      result = 31 * result + (status != null ? status.hashCode() : 0);
      return result;
    }

    public String toCSV() {
      return String.format("%s, %s, %s, %d, %s", this.project, this.ruleKey, this.path, this.line, this.status);
    }
  }

  public static void main(String[] args) {
    Path rulingRoot = Paths.get("its", "ruling");
    Path truth = rulingRoot.resolve(Paths.get("src", "test", "resources"));
    Path actual = rulingRoot.resolve(Paths.get("target", "actual"));
    final SingleFileDigest digest = new SingleFileDigest(truth, actual);
    try {
      digest.report();
    } catch (IOException e) {
      LOGGER.severe(e.getMessage());
    }
  }
}
