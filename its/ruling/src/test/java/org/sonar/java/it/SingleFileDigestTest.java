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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class SingleFileDigestTest {
  private static final Path ROOT_DIRECTORY = Paths.get("src", "test", "resources", "single-digest");
  private static final Path EMPTY_DIRECTORY = ROOT_DIRECTORY.resolve("empty-directory");
  private static final Path FALSE_POSITIVE_DIRECTORY = ROOT_DIRECTORY.resolve("actual-fp");
  private static final Path SOURCE_DIRECTORY = ROOT_DIRECTORY.resolve("truth");

  @BeforeClass
  public static void setup() throws IOException {
    if (!Files.isDirectory(EMPTY_DIRECTORY)) {
      Files.createDirectories(EMPTY_DIRECTORY);
    }
  }

  @AfterClass
  public static void tearDown() throws IOException {
    if (Files.exists(EMPTY_DIRECTORY)) {
      final List<Path> files = Files.walk(EMPTY_DIRECTORY)
        .filter(Files::isRegularFile)
        .collect(Collectors.toList());
      for (Path file : files) {
        Files.delete(file);
      }
      final List<Path> directories = Files.walk(EMPTY_DIRECTORY)
        .filter(Files::isDirectory)
        .sorted()
        .collect(Collectors.toList());
      for (Path directory : directories) {
        Files.deleteIfExists(directory);
      }
    }
    final List<Path> reportsToDelete = Files.walk(FALSE_POSITIVE_DIRECTORY)
      .filter(file -> Files.isRegularFile(file) && file.endsWith("report.csv"))
      .collect(Collectors.toList());
    for (Path report : reportsToDelete) {
      Files.delete(report);
    }
  }

  @Test
  public void should_throw_an_IllegalStateException_if_source_or_actual_directories_do_not_exist() {
    SingleFileDigest digest = new SingleFileDigest(Paths.get("non-existent"), Paths.get("non-existent"));
    IllegalStateException exception = assertThrows(IllegalStateException.class, digest::report);
    assertThat(exception).hasMessage("non-existent is not a valid source directory");

    digest = new SingleFileDigest(SOURCE_DIRECTORY, Paths.get("non-existent"));
    exception = assertThrows(IllegalStateException.class, digest::report);
    assertThat(exception).hasMessage("non-existent is not a valid actual directory");
  }

  @Test
  public void should_return_a_path_to_an_empty_file_if_there_are_no_differences_in_actual() throws IOException {
    SingleFileDigest digest = new SingleFileDigest(SOURCE_DIRECTORY, EMPTY_DIRECTORY);
    Path reportPath = digest.report();
    assertThat(reportPath)
      .isEqualTo(EMPTY_DIRECTORY.resolve("report.csv"))
      .exists()
      .isEmptyFile();
  }

  @Test
  public void should_return_a_path_to_a_file_with_differences() throws IOException {
    SingleFileDigest digest = new SingleFileDigest(SOURCE_DIRECTORY, FALSE_POSITIVE_DIRECTORY);
    Path reportPath = digest.report();
    assertThat(reportPath)
      .isEqualTo(FALSE_POSITIVE_DIRECTORY.resolve("report.csv"))
      .exists()
      .isNotEmptyFile();
  }
}
