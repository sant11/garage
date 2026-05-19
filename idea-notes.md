# Garage rental

### Główny problem

Aplikacja ma pomagać właścicielowi garaży aktywnie zarządzać najmem poprzez wykrywanie zaległości, pustostanów i problemów operacyjnych zanim przełożą się na utratę przychodów. System ma zamieniać dane o najmie w konkretne informacje i działania, zamiast pełnić rolę pasywnego „Excela online”.

---

### Najmniejszy zestaw funkcjonalnośći

## Zarządzanie strukturą
- lokalizacje
- garaże przypisane do lokalizacji
- status garażu (wolny, wynajęty, problem)

## Zarządzanie najmem
- najemcy
- umowy najmu
- historia najmu garażu

## Płatności
- rejestrowanie wpłat
- oznaczanie zaległości
- podgląd aktualnych należności

## Dashboard właściciela
Widok pokazujący:
- wolne garaże
- zaległe płatności
- kończące się umowy
- przychód miesięczny
- pustostany wymagające uwagi

## Alerty i analiza
- wykrywanie zaległości
- wykrywanie długo niewynajętych garaży
- oznaczanie najemców z częstymi opóźnieniami

## Historia zdarzeń i notatki
- notatki do garaży i najemców
- historia problemów i zdarzeń

---

### Co NIE wchodzi w zakres MVP

- aplikacja mobilna
- marketplace garaży
- integracje bankowe
- płatności online
- automatyczne faktury
- podpis elektroniczny
- zaawansowana księgowość
- AI/chatbot
- integracje SMS/BLIK
- role i rozbudowane uprawnienia użytkowników
- obsługa wielu firm/właścicieli (multi-tenant SaaS)
- integracje z bramami lub smart lockami
- rozbudowana analityka finansowa

---

### Kryteria sukcesu

## Operacyjne
- właściciel codziennie korzysta z dashboardu jako głównego centrum zarządzania
- wszystkie aktywne garaże są obsługiwane w systemie zamiast w Excelu

## Biznesowe
- system pozwala szybciej wykrywać zaległości i pustostany
- zmniejsza liczbę zapomnianych płatności i problemów organizacyjnych
- skraca czas potrzebny do obsługi najmu

## Produktowe
- właściciel po wejściu do systemu od razu wie:
  - gdzie traci pieniądze,
  - które garaże wymagają działania,
  - którzy najemcy generują ryzyko

## Techniczne
- dodanie nowego najmu zajmuje mniej niż kilka minut
- najważniejsze informacje są dostępne z poziomu jednego dashboardu
- system działa szybko i wygodnie również na telefonie
