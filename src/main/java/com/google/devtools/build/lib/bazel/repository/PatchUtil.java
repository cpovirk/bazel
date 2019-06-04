// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository;


import com.google.devtools.build.lib.vfs.Path;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import difflib.DiffUtils;
import difflib.Patch;
import difflib.PatchFailedException;

public class PatchUtil {

  private enum LineType {
    OLD_FILE,
    NEW_FILE,
    CHUNK_HEAD,
    CHUNK_BODY,
    UNKNOWN
  }

  private static LineType getLineType(String line, boolean isReadingChunk) {
    if (!isReadingChunk && line.startsWith("---")) {
      return LineType.OLD_FILE;
    }
    if (!isReadingChunk && line.startsWith("+++")) {
      return LineType.NEW_FILE;
    }
    if (line.startsWith("@@") && line.lastIndexOf("@@") != 0) {
      return LineType.CHUNK_HEAD;
    }
    if (isReadingChunk && (line.startsWith("-") || line.startsWith("+") || line.startsWith(" "))) {
      return LineType.CHUNK_BODY;
    }
    return LineType.UNKNOWN;
  }

  private static List<String> readFile(String file) throws IOException {
    return Files.readAllLines(new File(file).toPath());
  }

  private static void writeFile(String file, List<String> content) throws IOException {
    BufferedWriter writer = new BufferedWriter(new FileWriter(file));
    for (String line : content) {
      writer.write(line);
      writer.write("\n");
    }
    writer.close();
  }

  private static void applyPatchToFile(List<String> patchContent, String oldFile, String newfile)
      throws IOException, PatchFailedException {
    List<String> originContent = readFile(oldFile);
    Patch<String> patch = DiffUtils.parseUnifiedDiff(patchContent);
    List<String> newContent = DiffUtils.patch(originContent, patch);
    writeFile(newfile, newContent);
  }

  /**
   * Strip a number of leading components from a path
   * @param path the original path
   * @param strip the number of leading components to strip
   * @return The stripped path
   */
  private static String stripPath(String path, int strip) {
    int pos = 0;
    while (pos < path.length() && strip > 0) {
      if (path.charAt(pos) == '/') {
        strip--;
      }
      pos++;
    }
    return path.substring(pos);
  }

  /**
   * Apply a patch file under a directory
   * @param patchFile the patch file to apply
   * @param strip the number of leading components to strip from file path in the patch file
   * @param outputDirectory the repository directory to apply the patch file
   */
  public static void apply(String patchFile, int strip, Path outputDirectory) throws IOException, PatchFailedException {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(new FileInputStream(new File(patchFile))))) {
      String line;
      boolean isReadingChunk = false;
      List<String> patchContent = new ArrayList<>();
      String oldFile = null;
      String newFile = null;
      while ((line = reader.readLine()) != null) {
        switch (getLineType(line, isReadingChunk)) {
          case OLD_FILE:
            patchContent.add(line);
            // The line could look like:
            // --- foo/bar.txt   2019-05-27 17:19:37.054593200 +0200
            oldFile = stripPath(line.split("\t")[0].substring(4), strip);
            oldFile = outputDirectory.getRelative(oldFile).getPathString();
            break;
          case NEW_FILE:
            patchContent.add(line);
            // The line could look like:
            // +++ foo/bar.txt   2019-05-27 17:19:37.054593200 +0200
            newFile = stripPath(line.split("\t")[0].substring(4), strip);
            newFile = outputDirectory.getRelative(newFile).getPathString();
            break;
          case CHUNK_HEAD:
            int pos = line.lastIndexOf("@@");
            patchContent.add(line.substring(0, pos + 2));
            isReadingChunk = true;
            break;
          case CHUNK_BODY:
            patchContent.add(line);
            break;
          case UNKNOWN:
            if (!patchContent.isEmpty() && oldFile != null && newFile != null) {
              applyPatchToFile(patchContent, oldFile, newFile);
            }
            patchContent.clear();
            oldFile = null;
            newFile = null;
            isReadingChunk = false;
            break;
        }
      }
      if (!patchContent.isEmpty() && oldFile != null && newFile != null) {
        applyPatchToFile(patchContent, oldFile, newFile);
      }
    }
  }
}