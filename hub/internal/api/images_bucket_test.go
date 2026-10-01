package api

import "testing"

func TestBucketWidthOffersAFullScreenSize(t *testing.T) {
	cases := map[int]int{0: 360, 100: 180, 361: 540, 780: 780, 781: 1280, 1280: 1280, 4000: 1280}
	for requested, want := range cases {
		if got := bucketWidth(requested); got != want {
			t.Errorf("bucketWidth(%d) = %d, want %d", requested, got, want)
		}
	}
}
