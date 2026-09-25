package reading

import "testing"

func TestLookupRequiresAnUnambiguousStrongIdentifier(t *testing.T) {
	store := NewCatalogStore("")
	id, _ := store.Bind(WorkBinding{Source: "one", SourceID: "1", IdentityKeys: []string{"isbn:123", "title:book"}})
	if got, ok := store.FindIdentity("isbn:123"); !ok || got != id {
		t.Fatal("identifier not resolved")
	}
	if _, ok := store.FindIdentity("title:book"); ok {
		t.Fatal("weak identity resolved")
	}
	other, _ := store.Bind(WorkBinding{Source: "two", SourceID: "2"})
	store.works[other] = CatalogWork{ID: other, IdentityKeys: []string{"isbn:123"}}
	if _, ok := store.FindIdentity("isbn:123"); ok {
		t.Fatal("ambiguous identity resolved")
	}
}
