package io.forge.core

class FeatureIdSluggerSuite extends munit.FunSuite:

  import FeatureIdSlugger.{slugify, assignUnique, assign}

  // §5.2 table — every documented example must round-trip exactly.

  test("'Add Stripe webhook receiver' -> 'add-stripe-webhook-receiver'"):
    assertEquals(slugify("Add Stripe webhook receiver").value, "add-stripe-webhook-receiver")

  test("'Fix bug #1234' -> 'fix-bug-1234'"):
    assertEquals(slugify("Fix bug #1234").value, "fix-bug-1234")

  test("emoji and whitespace are stripped: 'rocket Launch' -> 'launch'"):
    assertEquals(slugify("🚀 Launch").value, "launch")

  test("digit-leading input gets the 'f-' prefix"):
    assertEquals(slugify("42-line-issue").value, "f-42-line-issue")

  // Additional algorithm coverage

  test("runs of non-[a-z0-9] collapse to a single hyphen"):
    assertEquals(slugify("foo!!!bar???baz").value, "foo-bar-baz")
    assertEquals(slugify("  leading and trailing  ").value, "leading-and-trailing")

  test("empty / all-symbols input yields 'f-' per step 5"):
    assertEquals(slugify("").value, "f-")
    assertEquals(slugify("!!!").value, "f-")

  test("oversize input truncates at the last hyphen within 40 chars"):
    // 47 chars; the last '-' at or before index 40 is at index 38.
    val title = "the-quick-brown-fox-jumps-over-the-lazy-dog-now"
    val slug  = slugify(title)
    assert(slug.value.length <= 40)
    assertEquals(slug.value, "the-quick-brown-fox-jumps-over-the-lazy")

  test("oversize input with no hyphen in window hard-truncates to 40"):
    val title = "a" * 60
    assertEquals(slugify(title).value, "a" * 40)

  // §5.2 collision suffix

  test("assignUnique returns the base slug if no collision"):
    val base  = FeatureId("hello")
    val taken = Set.empty[String]
    assertEquals(assignUnique(base, id => taken(id.value)).value, "hello")

  test("assignUnique appends '-2' on first collision"):
    val base  = FeatureId("hello")
    val taken = Set("hello")
    assertEquals(assignUnique(base, id => taken(id.value)).value, "hello-2")

  test("assignUnique keeps incrementing until a free slot is found"):
    val base  = FeatureId("hello")
    val taken = Set("hello", "hello-2", "hello-3")
    assertEquals(assignUnique(base, id => taken(id.value)).value, "hello-4")

  test("§5.2 example: second 'Add Stripe webhook receiver' becomes ...-2"):
    val taken = Set("add-stripe-webhook-receiver")
    assertEquals(
      assign("Add Stripe webhook receiver", id => taken(id.value)).value,
      "add-stripe-webhook-receiver-2"
    )
