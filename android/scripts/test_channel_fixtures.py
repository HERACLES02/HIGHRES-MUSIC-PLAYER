import hashlib
import json
import unittest
import channel_fixtures as fixtures


class ChannelTests(unittest.TestCase):
    def test_pinned_signals_are_identical_and_exclusive(self):
        manifest=json.loads(fixtures.MANIFEST.read_text(encoding='utf-8'))
        for item in manifest['fixtures']:
            rate=item['sampleRateHz']; bits=item['meaningfulBits']; width=bits//8
            pcm=fixtures.reference(rate,bits)
            with self.subTest(file=item['file']):
                self.assertEqual(len(pcm),rate*6*width*2)
                self.assertEqual(hashlib.sha256(pcm).hexdigest(),item['pcmSha256'])
                left=[int.from_bytes(pcm[p:p+width],'little',signed=True) for p in range(0,len(pcm),width*2)]
                right=[int.from_bytes(pcm[p+width:p+width*2],'little',signed=True) for p in range(0,len(pcm),width*2)]
                self.assertEqual(left[:rate*2],right[rate*3:rate*5])
                self.assertTrue(any(left[:rate*2]) and any(right[rate*3:rate*5]))
                self.assertTrue(all(v==0 for v in left[rate*2:]))
                self.assertTrue(all(v==0 for v in right[:rate*3]+right[rate*5:]))
                self.assertTrue(all(not(l and r) for l,r in zip(left,right)))
                self.assertLessEqual(max(map(abs,left+right)),((1<<(bits-1))-1)*.008)
                self.assertGreater(max(left),0); self.assertLess(min(left),0)
                if bits==24:
                    self.assertTrue(any(v & 255 for v in left if v))


if __name__=='__main__':
    unittest.main()
