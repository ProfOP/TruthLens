import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import claim_addresses, extract, extract_subject, faceforensics_verdict, gate_and_select, nli_prefilter_on, pre_nli_reject, title_core, welfake_verdict

# Deployed Iteration 2 best_parameters (experiment_report_v2.json) plus the new
# addressing gate default; tests pin these values instead of reading the file.
MS, MO, MN, MG, MA = 0.55, 0.3, 0.8, 0.2, 0.5

def cand(page_title, text, sim=0.80, overlap=0.80, ent=0.0, con=0.0, neu=1.0):
    return {"page_id": page_title.replace(" ", "_"), "sentence_id": 0, "page_title": page_title,
            "text": text, "similarity": sim, "overlap": overlap, "rank": 1, "selected": False,
            "rejected": None, "named_scores": {"entailment": ent, "contradiction": con, "neutral": neu},
            "nli_scores": [ent, con, neu], "nli_labels": ["ENTAILMENT", "CONTRADICTION", "NEUTRAL"]}

def with_scores(c, ent=0.0, con=0.0, neu=1.0):
    d = dict(c)
    d["named_scores"] = {"entailment": ent, "contradiction": con, "neutral": neu}
    d["nli_scores"] = [ent, con, neu]
    return d

MATRIX_CLAIM = "The Matrix is a 1999 science fiction film."
MATRIX_TRUE = cand("The Matrix", "The Matrix is a 1999 science fiction film written and directed by The Wachowskis , starring Keanu Reeves .", ent=0.9971, con=0.0005, neu=0.0024)
MATRIX_SEQUEL = cand("The Matrix Revolutions", "The Matrix Revolutions is a 2003 American -- Australian science fiction action film written and directed by The Wachowski Brothers .", sim=0.6474, con=0.9987, ent=0.0006, neu=0.0007)

DRAFT_CLAIM = "The 1968 NBA draft was the 22nd annual draft."
DRAFT_TRUE = cand("1968 NBA draft", "The 1968 NBA draft was the 22nd annual draft of the National Basketball Association ( NBA ) .", sim=0.9366, ent=0.9889, con=0.0010, neu=0.0101)
DRAFT_OTHER = cand("1984 NBA draft", "The 1984 NBA draft was the 37th annual draft of the National Basketball Association ( NBA ) .", sim=0.7173, con=0.9903, ent=0.0001, neu=0.0096)

JORDAN_CLAIM = "Michael Jordan retired to play baseball."
JORDAN_TRUE = cand("Michael Jordan", "Although Jordan abruptly retired from basketball before the beginning of the 1993 -- 94 NBA season to pursue a new venture in minor league baseball , he returned to the Bulls in March 1995 .", sim=0.7695, overlap=0.833, ent=0.9974, con=0.0003, neu=0.0023)
JORDAN_CAREER = cand("Michael Jordan", "Jordan played 15 seasons in the National Basketball Association ( NBA ) for the Chicago Bulls and Washington Wizards .", sim=0.5854, overlap=0.333, con=0.9964, ent=0.0, neu=0.0036)
JORDAN_HOF = cand("Michael Jordan", "Jordan is a two-time inductee into the Basketball Hall of Fame , having been enshrined in 2009 for his individual career , and again in 2010 as part of the group induction .", sim=0.60, overlap=0.4, con=0.9988, ent=0.0001, neu=0.0011)

EARTH_FIFTH = "Earth is the fifth planet from the Sun."
EARTH_THIRD = "Earth is the third planet from the Sun."
EARTH_TEXT = cand("Earth", "Earth is the third planet from the Sun and the only astronomical object known to harbor life .", sim=0.9, overlap=1.0)

def test_subject_extraction_handles_years_works_and_people():
    assert extract_subject(DRAFT_CLAIM) == "1968 NBA draft"
    assert extract_subject(MATRIX_CLAIM) == "Matrix"
    assert extract_subject(JORDAN_CLAIM) == "Michael Jordan"
    # A claim with no terminating verb yields no reliable subject, so the gate is skipped.
    assert extract_subject("Swimming is healthy") == "Swimming"
    assert extract_subject("Nine persons were present") == "Nine persons"
    assert title_core("The_Matrix_-LRB-franchise-RRB-") == "The Matrix"

def test_gate_rejects_other_edition_of_a_named_work():
    ok, why = claim_addresses(MATRIX_CLAIM, "The Matrix Revolutions",
                              "The Matrix Revolutions is a 2003 American science fiction action film .", MA)
    assert not ok and "more specific subject" in why

def test_gate_rejects_other_year_of_the_same_event():
    ok, why = claim_addresses(DRAFT_CLAIM, "1984 NBA draft",
                              "The 1984 NBA draft was the 37th annual draft of the National Basketball Association .", MA)
    assert not ok and ("more specific subject" in why or "different year" in why)

def test_gate_accepts_a_formal_name_of_the_same_ruler():
    # A regnal number followed by an "of ..." appellation names the same entity, not an
    # edition of it, so the subject test must not discard the page.
    claim = "George III was killed by his son."
    title = "George III of the United Kingdom"
    passage = "After a final relapse in 1810 , a regency was established , and George III 's eldest son , George , ruled as Prince Regent ."
    assert claim_addresses(claim, title, passage, MA)[0]
    # The subject test passes; any remaining rejection comes from the assertion channel.
    ok, why = claim_addresses(claim, title,
                              "On George III 's death , the Prince Regent succeeded his father as George IV .", MA)
    assert not ok and "more specific subject" not in why

def test_appellation_exception_does_not_extend_to_plain_names():
    # Without a regnal number, "X of Y" is still a different subject and must be rejected.
    ok, why = claim_addresses("Henry was a king.", "Henry of Scotland",
                              "Henry of Scotland was a prince of the Welsh court .", MA)
    assert not ok and "more specific subject" in why

def test_gate_rejects_unrelated_fact_about_the_same_entity():
    for text in (JORDAN_CAREER["text"], JORDAN_HOF["text"]):
        ok, why = claim_addresses(JORDAN_CLAIM, "Michael Jordan", text, MA)
        assert not ok and "assertion" in why

def test_gate_accepts_paraphrase_of_the_same_claim():
    assert claim_addresses(MATRIX_CLAIM, "The Matrix",
                           "The Matrix is a 1999 science fiction film written and directed by The Wachowskis .", MA)[0]
    assert claim_addresses("Paris is the capital of France.", "France",
                           "Paris is the capital and most populous city of France .", MA)[0]
    # Different surface wording, same subject and assertion.
    assert claim_addresses("Armstrong walked on the Moon in 1969.", "Neil Armstrong",
                           "On July 20 , 1969 , Armstrong became the first person to walk on the Moon .", MA)[0]
    assert claim_addresses(JORDAN_CLAIM, "Michael Jordan", JORDAN_TRUE["text"], MA)[0]

def test_gate_accepts_contradiction_that_addresses_the_claim():
    ok, why = claim_addresses(EARTH_FIFTH, "Earth", EARTH_TEXT["text"], MA)
    assert ok and why == ""
    assert claim_addresses(EARTH_THIRD, "Earth", EARTH_TEXT["text"], MA)[0]

def test_high_contradiction_alone_cannot_refute_matrix_sequel():
    ev = [dict(c) for c in (MATRIX_TRUE, MATRIX_SEQUEL)]
    verdict, selected = gate_and_select(MATRIX_CLAIM, ev, MS, MO, MN, MG, MA)
    assert verdict == "SUPPORTED" and selected["page_id"] == "The_Matrix"
    assert MATRIX_SEQUEL["page_id"].replace(" ", "_") not in [e["page_id"] for e in ev if e["selected"]]
    assert [e for e in ev if e["page_id"] == "The_Matrix_Revolutions"][0]["rejected"].startswith("passage is about a more specific subject")

def test_high_contradiction_alone_cannot_refute_other_year_draft():
    ev = [dict(c) for c in (DRAFT_TRUE, DRAFT_OTHER)]
    verdict, selected = gate_and_select(DRAFT_CLAIM, ev, MS, MO, MN, MG, MA)
    assert verdict == "SUPPORTED" and selected["page_id"] == "1968_NBA_draft"
    assert [e for e in ev if e["page_id"] == "1984_NBA_draft"][0]["rejected"]

def test_same_entity_contradictions_abstain_without_addressing_evidence():
    ev = [dict(c) for c in (JORDAN_CAREER, JORDAN_HOF)]
    verdict, selected = gate_and_select(JORDAN_CLAIM, ev, MS, MO, MN, MG, MA)
    assert verdict == "INSUFFICIENT" and selected is None
    assert all(e["selected"] is False for e in ev)
    assert all(e["rejected"] for e in ev)

def test_jordan_supported_when_passage_addresses_the_claim():
    ev = [dict(c) for c in (JORDAN_CAREER, JORDAN_HOF, JORDAN_TRUE)]
    verdict, selected = gate_and_select(JORDAN_CLAIM, ev, MS, MO, MN, MG, MA)
    assert verdict == "SUPPORTED" and selected["page_id"] == "Michael_Jordan" and selected["text"] == JORDAN_TRUE["text"]

def test_known_good_verdicts_survive_the_gate():
    ev = [with_scores(EARTH_TEXT, ent=0.9950, con=0.0010, neu=0.0040)]
    assert gate_and_select(EARTH_THIRD, ev, MS, MO, MN, MG, MA)[0] == "SUPPORTED"
    ev = [with_scores(EARTH_TEXT, ent=0.0008, con=0.9992, neu=0.0000)]
    verdict, selected = gate_and_select(EARTH_FIFTH, ev, MS, MO, MN, MG, MA)
    assert verdict == "REFUTED" and selected["selected"] is True
    ev = [with_scores(cand("France", "Paris is the capital and most populous city of France ."), ent=0.9977, con=0.0002, neu=0.0021)]
    assert gate_and_select("Paris is the capital of France.", ev, MS, MO, MN, MG, MA)[0] == "SUPPORTED"

def test_retrieval_floor_still_abstains():
    ev = [dict(MATRIX_TRUE, similarity=0.50)]
    verdict, _ = gate_and_select(MATRIX_CLAIM, ev, MS, MO, MN, MG, MA)
    assert verdict == "INSUFFICIENT" and ev[0]["rejected"] == "below min_similarity (retrieval)"

def test_contradiction_channel_keeps_definitional_disagreements():
    # "is a TV show" vs "is a sports drama film" shares no assertion word, but both
    # define the same subject, so the disagreement is a legitimate refutation.
    claim = "The Cincinnati Kid is a TV show."
    passage = "The Cincinnati Kid is a 1969 American sports drama film starring Robert Redford and Paul Newman ."
    assert claim_addresses(claim, "The Cincinnati Kid", passage, MA)[0]
    assert not claim_addresses(JORDAN_CLAIM, "Michael Jordan", JORDAN_HOF["text"], MA)[0]
    assert claim_addresses("Daenerys Targaryen is a real person.", "Daenerys Targaryen",
                           "Daenerys Targaryen is a fictional character from George R. R. Martin 's fictional universe .", MA)[0]

def test_addressing_is_asymmetric_between_the_two_channels():
    # Same subject, unrelated fact: entailment is allowed through (a weak model
    # channel that is measured as the noisier one for contradiction), refutation is not.
    claim = "Leonardo da Vinci painted the Mona Lisa."
    passage = "Leonardo di ser Piero da Vinci was an Italian polymath of the High Renaissance ."
    assert not claim_addresses(claim, "Leonardo da Vinci", passage, MA)[0]
    ev = [with_scores(cand("Leonardo da Vinci", passage), ent=0.9900, con=0.0010, neu=0.0090)]
    assert gate_and_select(claim, ev, MS, MO, MN, MG, MA)[0] == "SUPPORTED"
    ev = [with_scores(cand("Leonardo da Vinci", passage), con=0.9900, ent=0.0010, neu=0.0090)]
    assert gate_and_select(claim, ev, MS, MO, MN, MG, MA)[0] == "INSUFFICIENT"

def test_neutral_candidates_are_reported_as_inconclusive_with_a_reason():
    ev = [with_scores(cand("Michael Jordan", "Michael Jeffrey Jordan was born on February 17 , 1963 ."), neu=0.98)]
    verdict, selected = gate_and_select(JORDAN_CLAIM, ev, MS, MO, MN, MG, MA)
    assert verdict == "INSUFFICIENT" and selected is None
    assert ev[0]["rejected"].startswith("no conclusive NLI signal")

def test_extract_caps_three_claims():
    text = "Helsinki is the capital of Finland. The river is long. A third sentence is here. A fourth sentence is skipped."
    claims = extract(text, 3)
    assert len(claims) <= 3
    assert all("text" in claim for claim in claims)

def test_extract_filters_facebook_permission_notice():
    text = ("I do not give Facebook or Meta permission to use my pictures in any posts. "
            "By this statement, I give notice to Facebook that I do not consent. "
            "Copy and paste this to your wall within 10 minutes and you will win.")
    assert extract(text) == []

def test_extract_keeps_company_and_promo_assertions():
    assert [c["text"] for c in extract("Facebook was founded in 2004.")] == ["Facebook was founded in 2004."]
    claims = extract("Costco announced a giveaway for members. The event starts Monday.")
    assert claims and claims[0]["text"].startswith("Costco announced")

def test_extract_filters_instructions_and_interjections():
    assert extract("Hurry!") == []
    assert extract("Share this with your friends.") == []
    claims = extract("Do not share this post. Facebook was founded in 2004.")
    assert [c["text"] for c in claims] == ["Facebook was founded in 2004."]

def test_extract_handles_leading_quotes_and_questions():
    claims = extract('"Facebook was founded in 2004." The company grew fast.')
    assert [c["text"] for c in claims] == ['"Facebook was founded in 2004." The company grew fast.']
    assert extract("Is Facebook old? Facebook was founded in 2004.") != []

def test_welfake_deployment_threshold():
    assert welfake_verdict(0.047, 0.953, 0.70) == "FAKE"
    assert welfake_verdict(0.833, 0.167, 0.70) == "REAL"

def test_face_margin_unchanged_threshold():
    verdict, binary, confidence = faceforensics_verdict(0.433, 0.42, 0.05)
    assert binary == "MANIPULATED"
    assert verdict == "UNCERTAIN"
    assert abs(confidence - 0.433) < 1e-9

def test_shap_budget_is_an_int_bool_pair_and_clamps_invalid_values():
    # The endpoint unpacks this as `max_evals,overridden = shap_budget()`. Returning a bare int (or a
    # value shap cannot use) makes /v1/explanations/shap answer 200 with status "unavailable", so a
    # broken knob looks like a fast, empty explanation instead of an error.
    import os
    import app
    key = "TRUTHLENS_SHAP_MAX_EVALS"
    saved = os.environ.get(key)
    try:
        os.environ.pop(key, None)
        assert app.shap_budget() == (65, False)  # the shipped budget
        for raw, want in (("32", (32, True)), ("20.9", (20, True)), ("65", (65, True)),
                          ("0", (65, False)), ("-4", (65, False)), ("junk", (65, False)),
                          ("", (65, False)), ("  ", (65, False))):
            os.environ[key] = raw
            pair = app.shap_budget()
            assert pair == want, (raw, pair)
            assert isinstance(pair[0], int) and isinstance(pair[1], bool), (raw, pair)
    finally:
        os.environ.pop(key, None)
        if saved is not None:
            os.environ[key] = saved


PREFILTER_CLAIM = "Michael Jordan retired to play baseball."
PREF_A = cand("Some Page", "Unrelated sentence.", sim=0.40, overlap=0.9)          # below min_similarity
PREF_B = cand("Michael Jordan", "Jordan played 15 seasons in the NBA .", sim=0.70, overlap=0.2)  # below min_overlap
PREF_C = cand("Michael Jordan", "Jordan is a two-time inductee into the Basketball Hall of Fame .", sim=0.60, overlap=0.4)


def _scored(c, ent=0.0, con=0.0, neu=1.0):
    d = dict(c)
    d["named_scores"] = {"entailment": ent, "contradiction": con, "neutral": neu}
    d["nli_scores"] = [ent, con, neu]
    d["nli_labels"] = ["ENTAILMENT", "CONTRADICTION", "NEUTRAL"]
    d["selected"] = False
    d["rejected"] = None
    return d


def _unscored(c):
    d = dict(c)
    d["named_scores"] = None
    d["nli_scores"] = []
    d["nli_labels"] = []
    d["selected"] = False
    d["rejected"] = None
    return d


def test_nli_prefilter_flag_is_opt_in():
    import os
    key = "TRUTHLENS_NLI_PREFILTER"
    saved = os.environ.get(key)
    try:
        os.environ.pop(key, None)
        assert nli_prefilter_on() is False  # the deployed path scores every candidate
        for raw, want in (("1", True), ("on", True), ("YES", True), (" true ", True),
                          ("0", False), ("", False), ("  ", False), ("off", False), ("junk", False)):
            os.environ[key] = raw
            assert nli_prefilter_on() is want, (raw,)
    finally:
        os.environ.pop(key, None)
        if saved is not None:
            os.environ[key] = saved


def test_pre_nli_reject_uses_the_gate_order_and_the_gate_strings():
    assert pre_nli_reject(PREF_A, MS, MO) == "below min_similarity (retrieval)"
    assert pre_nli_reject(PREF_B, MS, MO) == "below min_overlap (lexical)"
    assert pre_nli_reject(PREF_C, MS, MO) is None
    # a row failing both is reported by the retrieval reason, exactly as the gate reports it
    both = cand("Some Page", "text", sim=0.10, overlap=0.0)
    assert pre_nli_reject(both, MS, MO) == "below min_similarity (retrieval)"


def test_prefiltering_which_candidates_are_scored_cannot_change_the_verdict():
    # The deployed path scores all four rows; the pre-filtered path scores only the two that pass
    # the retrieval and lexical checks.  Same verdict, same selected row, same rejection reasons,
    # same row order - the only difference is the probabilities on rows the gate never consulted.
    rows = [PREF_A, PREF_B, PREF_C, JORDAN_TRUE]
    full = [_scored(PREF_A), _scored(PREF_B), _scored(PREF_C, con=0.9988, ent=0.0001, neu=0.0011),
            _scored(JORDAN_TRUE, ent=0.9974, con=0.0003, neu=0.0023)]
    scored_ids = {i for i, c in enumerate(rows) if pre_nli_reject(c, MS, MO) is None}
    assert scored_ids == {2, 3}
    scores = {i: full[i]["nli_scores"] for i in scored_ids}
    pre = [_scored(rows[i], *scores[i]) if i in scored_ids else _unscored(rows[i])
           for i in range(len(rows))]
    v_full, s_full = gate_and_select(PREFILTER_CLAIM, full, MS, MO, MN, MG, MA)
    v_pre, s_pre = gate_and_select(PREFILTER_CLAIM, pre, MS, MO, MN, MG, MA)
    assert (v_full, s_full["page_id"], s_full["sentence_id"]) == (v_pre, s_pre["page_id"], s_pre["sentence_id"])
    assert [e["rejected"] for e in full] == [e["rejected"] for e in pre]
    assert [(e["page_id"], e["sentence_id"], e["similarity"], e["overlap"]) for e in full] == \
           [(e["page_id"], e["sentence_id"], e["similarity"], e["overlap"]) for e in pre]
    # the rows the pre-filter skipped keep their reason and lose only their probabilities
    assert full[0]["rejected"] == "below min_similarity (retrieval)" and pre[0]["nli_scores"] == []
    assert full[1]["rejected"] == "below min_overlap (lexical)" and pre[1]["nli_scores"] == []


if __name__ == "__main__":
    import inspect
    names = [n for n, f in sorted(globals().items()) if n.startswith("test_") and inspect.isfunction(f)]
    for n in names:
        globals()[n]()
    print(f"PASS ai-service/tests/test_rules.py ({len(names)} tests)")

