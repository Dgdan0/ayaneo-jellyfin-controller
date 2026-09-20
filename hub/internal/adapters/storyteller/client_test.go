package storyteller

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"

	"ayaneohub/internal/config"
)

func TestBooksUsesCredentialTokenAndReusesIt(t *testing.T) {
	var mu sync.Mutex
	tokenCalls := 0
	bookCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			tokenCalls++
			body, _ := io.ReadAll(r.Body)
			form, _ := url.ParseQuery(string(body))
			if r.Method != http.MethodPost || form.Get("usernameOrEmail") != "reader" || form.Get("password") != "secret" {
				t.Fatalf("token request = %s %q", r.Method, string(body))
			}
			_, _ = io.WriteString(w, `{"access_token":"token-1","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			mu.Lock()
			bookCalls++
			mu.Unlock()
			if r.Header.Get("Authorization") != "Bearer token-1" {
				t.Fatalf("Authorization = %q", r.Header.Get("Authorization"))
			}
			_, _ = io.WriteString(w, `[{"id":12,"uuid":"book-uuid","title":"Red Rising","language":"en","authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"ebook-uuid","pageCount":400},"audiobook":{"uuid":"audio-uuid","duration":7200},"position":{"locator":{"locations":{"totalProgression":0.25}}}}]`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		books, err := client.Books(context.Background())
		if err != nil || len(books) != 1 || books[0].Title != "Red Rising" || books[0].Ebook == nil || books[0].Audiobook == nil {
			t.Fatalf("Books() = %+v, %v", books, err)
		}
	}
	if tokenCalls != 1 || bookCalls != 2 {
		t.Fatalf("token calls = %d, book calls = %d", tokenCalls, bookCalls)
	}
}

func TestBookAndCoverRetryOnceAfterExpiredToken(t *testing.T) {
	tokenCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			tokenCalls++
			_, _ = io.WriteString(w, `{"access_token":"token-`+string(rune('0'+tokenCalls))+`","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/12":
			if r.Header.Get("Authorization") == "Bearer token-1" {
				http.Error(w, "expired", http.StatusUnauthorized)
				return
			}
			_, _ = io.WriteString(w, `{"id":12,"uuid":"book-uuid","title":"Red Rising","readaloud":{"uuid":"ra-uuid"}}`)
		case "/api/v2/books/12/cover":
			if r.Header.Get("Authorization") != "Bearer token-2" {
				t.Fatalf("cover Authorization = %q", r.Header.Get("Authorization"))
			}
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("jpeg"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	book, err := client.Book(context.Background(), 12)
	if err != nil || book.Readaloud == nil {
		t.Fatalf("Book() = %+v, %v", book, err)
	}
	body, contentType, err := client.Cover(context.Background(), 12)
	if err != nil || string(body) != "jpeg" || contentType != "image/jpeg" {
		t.Fatalf("Cover() = %q, %q, %v", body, contentType, err)
	}
	if tokenCalls != 2 {
		t.Fatalf("token calls = %d", tokenCalls)
	}
}
