"""Fixture-generation regressions independent of Android and external encoders."""
import hashlib
import json
import unittest

import fixtures


class FixtureTests(unittest.TestCase):
    def test_stereo_pattern_and_meaningful_low_bits(self):
        for bits in (16, 24):
            width = bits // 8
            pcm = fixtures.reference(44100, bits)
            frames = [(int.from_bytes(pcm[i:i + width], 'little', signed=True),
                       int.from_bytes(pcm[i + width:i + 2 * width], 'little', signed=True))
                      for i in range(0, len(pcm), 2 * width)]
            self.assertEqual(len(frames), 44100 * 6)
            self.assertTrue(all(right == 0 for _, right in frames[:88200]))
            self.assertTrue(all(left == 0 for left, _ in frames[88200:176400]))
            self.assertTrue(any(left and right for left, right in frames[176400:]))
            values = [value for pair in frames for value in pair]
            self.assertLess(min(values), 0)
            self.assertGreater(max(values), 0)
            self.assertLessEqual(max(abs(value) for value in values), ((1 << (bits - 1)) - 1) * .008)
            if bits == 24:
                self.assertTrue(any(value > 0 and value & 255 for value in values))
                self.assertTrue(any(value < 0 and value & 255 for value in values))

    def test_pinned_reference_pcm(self):
        manifest = json.loads(fixtures.MANIFEST.read_text(encoding='utf-8'))
        for item in manifest['fixtures']:
            with self.subTest(file=item['file']):
                pcm = fixtures.reference(item['sampleRateHz'], item['meaningfulBits'])
                self.assertEqual(hashlib.sha256(pcm).hexdigest(), item['pcmSha256'])
                self.assertEqual(len(pcm), item['sampleRateHz'] * 6 * 2 * (item['meaningfulBits'] // 8))


if __name__ == '__main__':
    unittest.main()
