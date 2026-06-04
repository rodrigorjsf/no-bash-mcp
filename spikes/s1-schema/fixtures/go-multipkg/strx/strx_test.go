package strx

import "testing"

func TestReverse(t *testing.T) {
	if Reverse("abc") != "cba" {
		t.Errorf("Reverse(abc) = %q, want cba", Reverse("abc"))
	}
}

// All subtests pass: verifies passing subtests produce NO findings and the parent
// is not spuriously surfaced.
func TestReverseTable(t *testing.T) {
	cases := map[string]string{"abc": "cba", "": "", "ab": "ba", "racecar": "racecar"}
	for in, want := range cases {
		t.Run(in, func(t *testing.T) {
			if got := Reverse(in); got != want {
				t.Errorf("Reverse(%q) = %q, want %q", in, got, want)
			}
		})
	}
}

// Deep nesting (Test/group/case): the deepest case fails, exercising a path[] of
// depth 2 — leaf "fails" with path=["TestReverseDeep","unicode"].
func TestReverseDeep(t *testing.T) {
	t.Run("ascii", func(t *testing.T) {
		if Reverse("xy") != "yx" {
			t.Errorf("ascii broke")
		}
	})
	t.Run("unicode", func(t *testing.T) {
		t.Run("fails", func(t *testing.T) {
			// WRONG on purpose: "áé" reversed is "éá", not "aé".
			if Reverse("áé") != "aé" {
				t.Errorf("Reverse(áé) = %q, want aé (deliberately wrong)", Reverse("áé"))
			}
		})
	})
}
