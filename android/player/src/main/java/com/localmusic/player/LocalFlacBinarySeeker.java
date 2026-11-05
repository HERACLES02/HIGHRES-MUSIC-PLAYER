/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.localmusic.player;

import androidx.media3.extractor.BinarySearchSeeker;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.FlacFrameReader;
import androidx.media3.extractor.FlacStreamMetadata;
import java.io.IOException;

/** Media3 1.8.0 FlacBinarySearchSeeker fallback; moved here to share its map with a private index.
 * Original: androidx/media libraries/extractor/.../flac/FlacBinarySearchSeeker.java (Apache-2.0).
 * Constants are inlined because upstream FlacConstants and this seeker are package-private.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class LocalFlacBinarySeeker extends BinarySearchSeeker {
  LocalFlacBinarySeeker(FlacStreamMetadata info, int marker, long first, long length) {
    super(info::getSampleNumber, new Timestamps(info, marker), info.getDurationUs(),
        0, info.totalSamples, first, length, info.getApproxBytesPerFrame(), Math.max(6, info.minFrameSize));
  }
  void cancel() { if (isSeeking()) markSeekOperationFinished(false, 0); }

  private static final class Timestamps implements TimestampSeeker {
    private final FlacStreamMetadata info;
    private final int marker;
    private final FlacFrameReader.SampleNumberHolder sample = new FlacFrameReader.SampleNumberHolder();
    Timestamps(FlacStreamMetadata info, int marker) { this.info = info; this.marker = marker; }
    @Override public TimestampSearchResult searchForTimestamp(ExtractorInput input, long target) throws IOException {
      long start = input.getPosition();
      long left = next(input), leftPosition = input.getPeekPosition();
      input.advancePeekPosition(Math.max(6, info.minFrameSize));
      long right = next(input), rightPosition = input.getPeekPosition();
      if (left <= target && right > target) return TimestampSearchResult.targetFoundResult(leftPosition);
      if (right <= target) return TimestampSearchResult.underestimatedResult(right, rightPosition);
      return TimestampSearchResult.overestimatedResult(left, start);
    }
    private long next(ExtractorInput input) throws IOException {
      while (input.getPeekPosition() < input.getLength() - 6 &&
          !FlacFrameReader.checkFrameHeaderFromPeek(input, info, marker, sample)) input.advancePeekPosition(1);
      if (input.getPeekPosition() >= input.getLength() - 6) {
        input.advancePeekPosition((int) (input.getLength() - input.getPeekPosition()));
        return info.totalSamples;
      }
      return sample.sampleNumber;
    }
  }
}
