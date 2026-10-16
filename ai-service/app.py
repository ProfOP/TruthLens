"""TruthLens local AI service using only exported local artifacts."""
from __future__ import annotations
import io, json, os, re, threading
from pathlib import Path
from typing import Any
import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

import related_passages

ROOT=Path(os.getenv("TRUTHLENS_ROOT",Path(__file__).resolve().parents[1])); MODELS=ROOT/"models"; app=FastAPI(title="TruthLens AI Service",version="0.2.0")
_lock=threading.RLock(); _loaded:dict[str,Any]={}
def deployment_float(name:str, fallback:float)->tuple[float,bool]:
    raw=os.getenv(name)
    if raw is None or not raw.strip(): return float(fallback),False
    try:return float(raw),True
    except ValueError:return float(fallback),False
def shap_budget()->tuple[int,bool]:
    """TRUTHLENS_SHAP_MAX_EVALS (default 65 = the shipped budget). A non-numeric or below-one value
    falls back to the default and is reported as such, instead of reaching shap with a call it cannot
    satisfy - that would surface as an 'unavailable' explanation rather than an error."""
    value,overridden=deployment_float("TRUTHLENS_SHAP_MAX_EVALS",65.0)
    evaluations=int(value)
    if evaluations<1: return 65,False
    return evaluations,overridden
def welfake_verdict(real:float,fake:float,threshold:float)->str:
    return "UNCERTAIN" if max(real,fake)<threshold else ("REAL" if real>=fake else "FAKE")
def faceforensics_verdict(manipulated:float,binary_threshold:float,uncertain_margin:float)->tuple[str,str,float]:
    binary="MANIPULATED" if manipulated>=binary_threshold else "AUTHENTIC"
    confidence=manipulated if binary=="MANIPULATED" else 1.0-manipulated
    verdict="UNCERTAIN" if abs(manipulated-binary_threshold)<uncertain_margin else binary
    return verdict,binary,confidence
def read_json(p:Path)->dict:
    try:return json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:return {"error":str(e)}
def dev():
    import torch; return torch.device("cuda" if torch.cuda.is_available() else "cpu")
def artifact(p:Path,name:str,c:Path|None=None)->dict:return {"name":name,"available":p.exists(),"artifact":str(p.relative_to(ROOT)) if p.exists() else str(p),"config":read_json(c) if c else {}}
def sentences(t:str):return [x.strip() for x in re.split(r"(?<=[.!?])\s+",t.strip()) if x.strip() and not x.rstrip().endswith("?")]
# Sentence-initial words that mark clear instructions/imperatives rather than
# factual assertions. Kept deterministic; eligibility is not truth.
IMPERATIVE_STARTERS={
    "copy","paste","share","post","repost","retweet","tag","forward","send","tell",
    "click","hurry","subscribe","like","comment","sign","register","download",
    "upload","get","make","take","go","wait","add","remove","delete","avoid",
    "ignore","remember","remind","ask","reply","message","call","email","print",
    "save","install","enable","disable","restart","reset","retry","refresh",
    "reload","navigate","scroll","swipe","tap","press","drag","drop","select",
    "choose","pick","buy","purchase","order","book","reserve","pay","donate",
    "vote","join","follow","report","flag","block","mute","ban","hide","pin",
    "archive","please",
}
# Standalone first-person consent/permission/notice declarations (e.g. "I do not
# give ... permission", "By this statement, I give notice ...").
CONSENT_PATTERNS=(
    r"^i (do not|don't|cannot|can't|will not|won't|am not) (give|grant|consent|agree|allow|permit|authorize|authorise)",
    r"^i (give|grant) (notice|permission|consent|authorization|authorisation|access)",
    r"^i hereby (give|grant|consent)",
    r"^by (this|posting|sharing|reposting)\b",
)
PERFORMATIVE_PATTERNS=(
    r"^all (you|u) (have to|need to|gotta|got to) do\b",
    r"\bjust (like|share|comment|repost|forward|tag|copy|paste|send|click)\b",
    r"\b(like|share|comment|repost|retweet|forward|tag)\b[^.]{0,40}\b(this|the|my)\b[^.]{0,20}\b(page|post|wall|photo|pic|picture|status|article|link)\b",
    r"\bto your (wall|timeline|story|page|status|feed)\b",
    r"\bcomment\s+['\"“‘]?done\b",
    r"\bshare (this|it) (to|with)\b",
    r"\btag a friend\b",
)
# Sentence-initial pronouns mark vague references; they are demoted (not dropped)
# so explicit-subject assertions are preferred when candidates exceed the limit.
VAGUE_PRONOUNS={"it","its","they","them","their","he","she","him","her","we","us","our","you","your","this","these","that","those","there"}
def _normalized(s:str)->str:
    return s.strip().strip("\"'“”‘’`()[]{}").strip()
def _is_instruction(s:str)->bool:
    n=_normalized(s)
    if not n or n.endswith("?"): return True
    first=re.match(r"[A-Za-z']+",n)
    if not first: return False
    w=first.group(0).lower()
    if w in IMPERATIVE_STARTERS: return True
    if re.match(r"^(do not|don't|dont)\b",n,re.I): return True
    if re.match(r"^(make|be) sure\b",n,re.I): return True
    if any(re.search(p,n,re.I) is not None for p in CONSENT_PATTERNS): return True
    return any(re.search(p,n,re.I) is not None for p in PERFORMATIVE_PATTERNS)
def extract(t:str,limit:int=3):
    a=[]
    for i,s in enumerate(sentences(t)):
        if _is_instruction(s): continue
        n=_normalized(s); first=re.match(r"[A-Za-z']+",n); vague=bool(first and first.group(0).lower() in VAGUE_PRONOUNS)
        w=re.findall(r"\b\w+\b",s); m=sum(bool(re.search(p,s,re.I)) for p in (r"\b(19|20)\d{2}\b",r"\b(is|are|was|were|has|have|will|can)\b",r"\d")); score=min(1.,.25+.05*len(w)+.12*m)-(.25 if vague else 0); a.append((score,i,s))
    return [{"text":s,"sentence_index":i,"relevance":round(sc,3),"method":"deterministic heuristic baseline"} for sc,i,s in sorted(a,reverse=True)[:limit]]
class TextRequest(BaseModel):title:str=""; body:str=Field(min_length=1,max_length=100000)
class ClaimRequest(BaseModel):claim:str=Field(min_length=3,max_length=2000)
def status():return {"components":{"welfake":artifact(MODELS/"Welfake/results/truthlens_distilbert","WELFake",MODELS/"Welfake/results/truthlens_text_model_config.json"),"faceforensics":artifact(MODELS/"FFC++/truthlens_faceforensics_model/efficientnet_b0_improved.pt","FaceForensics++",MODELS/"FFC++/truthlens_faceforensics_model/model_config.json"),"liar":artifact(MODELS/"Liar/truthlens_liar_models/liar_classifier.joblib","LIAR",MODELS/"Liar/truthlens_liar_models/model_config.json"),"fever":artifact(MODELS/"Fever/truthlens_fever_iteration2/fever_evidence_v2.index","FEVER Iteration 2",MODELS/"Fever/truthlens_fever_iteration2/experiment_report_v2.json")}}
@app.get("/health")
def health():return {"status":"ok","service":"ai-service","device":str(dev()),"nli_prefilter":{"enabled":nli_prefilter_on(),"note":"when on, candidates the retrieval/lexical gate rejects before the NLI test are not sent to the model"},"related_passages":{"enabled":related_passages.flag_on(),"k":related_passages.K,"router_loaded":_rp_router is not None,"router_error":_rp_error,"page_memo_limit_pages":related_passages.PAGE_MEMO_MAX_PAGES,"vector_cache_limit_rows":related_passages.VECTOR_CACHE_MAX_ROWS},**status()}
@app.get("/ready")
def ready():
    s=status();return {"ready":any(v["available"] for v in s["components"].values()),**s}
def load_welfake():
    with _lock:
        if "welfake" in _loaded:return _loaded["welfake"]
        from transformers import AutoTokenizer,AutoModelForSequenceClassification
        p=MODELS/"Welfake/results/truthlens_distilbert"; x=(AutoTokenizer.from_pretrained(p,local_files_only=True),AutoModelForSequenceClassification.from_pretrained(p,local_files_only=True).to(dev()).eval()); _loaded["welfake"]=x; return x
@app.post("/v1/classify/article")
def classify_article(r:TextRequest):
    import torch
    c=read_json(MODELS/"Welfake/results/truthlens_text_model_config.json"); n=int(c.get("max_length",256)); tok,model=load_welfake(); z=tok(f"{r.title.strip()} {r.body.strip()}".strip(),truncation=True,max_length=n,return_tensors="pt"); z={k:v.to(dev()) for k,v in z.items() if k in {"input_ids","attention_mask"}}
    with torch.inference_mode():p=torch.softmax(model(**z).logits,-1)[0].cpu().tolist()
    real,fake=map(float,p); exported_threshold=float(c.get("uncertain_threshold",.96)); th,overridden=deployment_float("TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD",exported_threshold); lab=welfake_verdict(real,fake,th)
    return {"label":lab,"probabilities":{"REAL":real,"FAKE":fake},"credibility_score":round(real*100,2),"truncated":bool(z["input_ids"].shape[1]>=n),"model":"WELFake DistilBERT","config":c,"deployment_overrides":{"text_confidence_threshold":th,"source":"deployment_override" if overridden else "exported_model_config","exported_model_threshold":exported_threshold}}
@app.post("/v1/extract-claims")
def extract_claims(r:TextRequest):return {"claims":extract(r.body)}
def load_liar():
    with _lock:
        if "liar" in _loaded:return _loaded["liar"]
        from sentence_transformers import SentenceTransformer
        import joblib,faiss,pandas as pd
        b=MODELS/"Liar/truthlens_liar_models"; x=(SentenceTransformer(str(b/"minilm_encoder"),device=str(dev())),joblib.load(b/"liar_classifier.joblib"),faiss.read_index(str(b/"liar_claims.index")),pd.read_csv(b/"claim_metadata.csv")); _loaded["liar"]=x; return x
@app.post("/v1/classify/liar")
def classify_liar(r:ClaimRequest):
    try:
        enc,clf,idx,meta=load_liar(); e=enc.encode([r.claim],normalize_embeddings=True); pred=int(clf.predict(e)[0]); pr=clf.predict_proba(e)[0]; names={0:"pants-fire",1:"false",2:"barely-true",3:"half-true",4:"mostly-true",5:"true"}; D,I=idx.search(np.asarray(e,dtype="float32"),5); sims=[{"claim":str(meta.iloc[int(i)]["statement"]),"label":str(meta.iloc[int(i)]["label"]),"similarity":float(s)} for s,i in zip(D[0],I[0]) if i>=0]; return {"label":names.get(pred,str(pred)),"probabilities":{names.get(int(k),str(k)):float(v) for k,v in zip(clf.classes_,pr)},"similar_claims":sims,"experimental":True,"note":"Relatedness is not supporting evidence."}
    except Exception as e:raise HTTPException(503,{"code":"LIAR_UNAVAILABLE","message":str(e)})
from functools import lru_cache
@lru_cache(maxsize=1)
def _stopwords():
    from sklearn.feature_extraction.text import ENGLISH_STOP_WORDS
    return set(ENGLISH_STOP_WORDS)
def clean_wiki_text(t:str)->str:
    t=str(t)
    for o,n in {"-LRB-":"(","-RRB-":")","-LSB-":"[","-RSB-":"]","-LCB-":"{","-RCB-":"}"}.items():t=t.replace(o,n)
    return re.sub(r"\s+"," ",t).strip()
def content_words(t:str)->set:
    sw=_stopwords();return {w for w in re.findall(r"[a-z0-9]+",str(t).lower()) if w not in sw and len(w)>1}
def lexical_overlap(claim:str,ev:str)->float:
    cw,ew=content_words(claim),content_words(ev)
    return 0.0 if not cw or not ew else len(cw&ew)/len(cw)
# Copulas/auxiliaries that end a leading subject noun phrase. A lowercase
# content verb ending in -ed/-ing also ends it ("Jordan retired ...").
NP_CUTTERS={"is","am","are","was","were","be","been","being","has","have","had","do","does","did",
    "will","would","can","could","may","might","must","shall","should","seem","seems","become",
    "becomes","became","remain","remains","stand","stands","sit","sits"}
def title_core(page_id:str)->str:
    # Disambiguator suffixes ("The Matrix (franchise)") do not identify a distinct subject.
    t=clean_wiki_text(str(page_id).replace("_"," "))
    return re.sub(r"\((?:[^()]*)\)|\[[^\]]*\]"," ",t).strip()
def _roots(t:str)->set:
    out=set()
    for w in content_words(t):
        for cand in (w,re.sub(r"(?:ies|ing|ed|es|s)$","",w) or w):
            if len(cand)>2: out.add(cand)
    return out
def _matched_by_root(word:str,roots:set)->bool:
    if word in roots: return True
    for r in roots:
        if len(word)>=4 and len(r)>=4 and (r.startswith(word) or word.startswith(r)): return True
    return False
# "S is a/the P" - a definitional claim. Two definitions of the same subject address
# each other even when the disputed predicate nominal shares no word ("TV show" vs
# "drama film", "real person" vs "fictional character"), so lexical overlap alone
# would discard legitimate refutations.
DEFINITIONAL=re.compile(r"\b(?:is|are|was|were)\s+(?:a|an|the|one|its|his|her|their)\b",re.I)
def extract_subject(claim:str)->str:
    toks=[t for t in re.split(r"\s+",str(claim).strip()) if t]
    out=[]
    for i,raw in enumerate(toks):
        w=re.sub(r"[^a-z0-9]","",raw.lower())
        if i>0 and (w in NP_CUTTERS or bool(re.fullmatch(r"[a-z][a-z]+(?:ed|ing)",w))): break
        out.append(raw)
    s=re.sub(r"^(the|a|an)\s+","", " ".join(out).strip(" .,;:\"'"),count=1,flags=re.I).strip()
    # A subject that never terminates is an extraction failure, not a proper noun phrase.
    return s if 0<len(s.split())<=6 and len(content_words(s))>=1 and len(s.split())<len(toks) else ""
def _ordered_words(t:str)->list:
    sw=_stopwords()
    return [w for w in re.findall(r"[a-z0-9]+",str(t).lower()) if w not in sw and len(w)>1]
REGNAL=re.compile(r"(?:[0-9]+|i{1,3}|iv|v|vi{0,3}|ix|x|xi|xii|xiii|xiv|xv)",re.I)
def _is_appellation(subject:str,title:str)->bool:
    """True when `title` is `subject` followed by an "of ..." appellation and the subject
    ends in a regnal number, i.e. "George III of the United Kingdom" for "George III".
    The numeral is what distinguishes a formal long name of the same person from a
    sequel or re-release, which has no regnal number to extend."""
    s=[w.lower() for w in re.findall(r"[A-Za-z0-9]+",str(subject))]; t=[w.lower() for w in re.findall(r"[A-Za-z0-9]+",str(title))]
    return len(t)>len(s)>0 and t[:len(s)]==s and t[len(s)]=="of" and REGNAL.fullmatch(s[-1]) is not None
def claim_addresses(claim:str,page_title:str,passage:str,min_assertion_overlap:float,require_assertion:bool=True)->tuple[bool,str]:
    """Deterministic gate: the passage must be about the claim's subject, and (when
    require_assertion) must address its assertion. Stops a high NLI score alone from
    making an unrelated passage conclusive."""
    subject=extract_subject(claim); premise=f"{page_title}. {passage}"
    cs=_ordered_words(subject) if subject else []
    proots=_roots(premise)
    if cs:
        tc=_ordered_words(title_core(page_title))
        if tc and not (set(tc)&set(cs)):
            if not all(_matched_by_root(w,proots) for w in cs):return False,"passage does not name the claim's subject"
        elif tc and set(tc)-set(cs):
            # The passage names more than the claim does. An abbreviated name is fine
            # ("Jordan" for "Michael Jordan"); a different trailing name is a distinct
            # work or edition ("Matrix" for "Matrix Revolutions") - unless the extra
            # words are an appellation phrase ("of the United Kingdom") attached to a
            # regnal number, which names the same entity, not a later edition.
            if not _is_appellation(subject,title_core(page_title)) and tc[-1]!=cs[-1]:return False,f"passage is about a more specific subject ({title_core(page_title)})"
            cn={w for w in cs if w.isdigit()}
            if cn:
                if cn!={w for w in tc if w.isdigit()} or not all(_matched_by_root(w,proots) for w in cn):
                    return False,"passage refers to a different year or edition"
    assertion=content_words(claim)-set(cs) if require_assertion else set()
    if assertion:
        hit={w for w in assertion if _matched_by_root(w,proots)}
        frac=len(hit)/len(assertion)
        if frac<min_assertion_overlap:
            if cs and DEFINITIONAL.search(claim) and DEFINITIONAL.search(passage):return True,""
            miss=", ".join(sorted(assertion-hit)[:6]) or "none"
            return False,f"passage does not address the claim's assertion (unmatched terms: {miss})"
    return True,""
def nli_label_map(model)->dict:
    # name(lower) -> index, from the model's own id2label (verified mapping).
    return {str(n).lower():int(i) for i,n in model.config.id2label.items()}
def load_fever():
    with _lock:
        if "fever" in _loaded:return _loaded["fever"]
        from sentence_transformers import SentenceTransformer
        from transformers import AutoTokenizer,AutoModelForSequenceClassification
        import faiss,pandas as pd
        b=MODELS/"Fever"; x=(SentenceTransformer(str(b/"truthlens_fever_models/minilm_encoder"),device=str(dev())),faiss.read_index(str(b/"truthlens_fever_iteration2/fever_evidence_v2.index")),pd.read_csv(b/"truthlens_fever_iteration2/evidence_metadata_v2.csv"),AutoTokenizer.from_pretrained(b/"truthlens_fever_models/nli_model",local_files_only=True),AutoModelForSequenceClassification.from_pretrained(b/"truthlens_fever_models/nli_model",local_files_only=True).to(dev()).eval()); _loaded["fever"]=x; return x
_rp_router=None; _rp_error=None
def related_router():
    """Lazily open the optional discovery assets. A failure is remembered, not retried per request."""
    global _rp_router,_rp_error
    if _rp_router is not None:return _rp_router
    with _lock:
        if _rp_router is None and _rp_error is None:
            try:_rp_router=related_passages.Router()
            except Exception as e:_rp_error=str(e)
    if _rp_error:raise RuntimeError(_rp_error)
    return _rp_router
def related_passages_for(claim:str,q:np.ndarray,cand:list)->dict|None:
    """Candidate-evidence discovery (FEVER page-title pilot, frozen merge@N=5).

    Runs only after gate_and_select has returned, over candidates the deployed path already
    scored; no passage from here reaches the NLI gate or the verdict. Returns None when the flag
    is off, so the default response is byte-identical to the deployed service.
    """
    if not related_passages.flag_on():return None
    try:
        rp=related_router(); enc,idx,_,_,_=load_fever(); rp.attach(enc,idx)
        faiss_c=[{"page_id":c["page_id"],"sentence_id":c["sentence_id"],"text":c["text"],
                  "score":c["similarity"],"source":"deployed_v2","route":"faiss"} for c in cand]
        out=rp.related(claim,np.asarray(q[0],dtype=np.float32),faiss_c)
        st=out["route_stats"]; snap=rp.snapshot()
        return {"status":"ok","flag":related_passages.FLAG,"k":related_passages.K,
                "heading":"Related passages - candidate evidence, not used for this verdict",
                "discovery_only":True,"used_for_verdict":False,
                "note":("Retrieved by the optional page-title discovery pilot (frozen merge@N=5: "+
                        "deployed FAISS top-10 merged with the top-10 of 5 title-searched pages). "+
                        "These passages were NOT scored by the NLI gate and did NOT establish the "+
                        "verdict above; the pilot measured 64.5% exact annotated gold-evidence "+
                        "recall@5 on unseen FEVER claims, which is still below the >80% target."),
                "passages":out["candidates"],"timing_ms":{"discovery_total":out["total_ms"],
                "title_select":st["select_ms"],"title_pages_fetch":st["score_ms"]},
                "stats":{"pages_selected":st["pages"],"pages_skipped":st["skipped_pages"],
                         "sentences_searched":st["sentences"],"sentences_newly_encoded":st["encoded"],
                         "merged_pool":out["merged_pool"]},"cache":snap["page_memo"],
                "vector_cache":snap["vector_cache"]}
    except Exception as e:
        return {"status":"unavailable","flag":related_passages.FLAG,"discovery_only":True,
                "used_for_verdict":False,"passages":[],"error":str(e),
                "note":"Candidate discovery failed; the verdict and evidence above are the deployed FAISS result, unchanged."}
def pre_nli_reject(c:dict,ms:float,mo:float)->str|None:
    """The two checks gate_and_select applies BEFORE a candidate's NLI probabilities can matter.
    One predicate, used by the gate and by the optional pre-filter, so they can never disagree
    about which candidates are worth a model call."""
    if c["similarity"]<ms:return "below min_similarity (retrieval)"
    if c["overlap"]<mo:return "below min_overlap (lexical)"
    return None
def nli_prefilter_on()->bool:
    """TRUTHLENS_NLI_PREFILTER (default OFF = the deployed path scores every retrieved candidate).
    When ON, only candidates that could reach the NLI test are sent to the model; a skipped row
    keeps its page, sentence, similarity, overlap and rejection reason and reports empty
    nli_scores/nli_labels.  Its probabilities could not have changed the verdict, because the gate
    rejects it on retrieval or lexical overlap first - which is what pre_nli_reject encodes."""
    return (os.getenv("TRUTHLENS_NLI_PREFILTER") or "").strip().lower() in {"1","true","on","yes"}
def gate_and_select(claim:str,ev:list,ms:float,mo:float,mn:float,mg:float,ma:float)->tuple[str,dict|None]:
    """Retrieval + lexical + claim-addressing gates, then verdict selection.
    A candidate must be about the claim's subject before its NLI score can contribute;
    a refutation must additionally address the assertion, because a correct refutation
    normally states something different where the claim is wrong, so its vocabulary
    cannot be the only check, while an unrelated fact about the same subject shows
    contradiction with almost no shared assertion terms."""
    hits=[]
    for e in ev:
        rej=pre_nli_reject(e,ms,mo)
        if rej:e["rejected"]=rej;continue
        ent,con,neu=e["named_scores"]["entailment"],e["named_scores"]["contradiction"],e["named_scores"]["neutral"]
        if ent>=mn and ent-max(con,neu)>=mg:v,s,strict="SUPPORTED",ent,False
        elif con>=mn and con-max(ent,neu)>=mg:v,s,strict="REFUTED",con,True
        else:
            e["rejected"]="no conclusive NLI signal (entailment/contradiction below threshold or margin)"
            continue
        ok,why=claim_addresses(claim,e.get("page_title",""),e["text"],ma,strict)
        if not ok:e["rejected"]=why;continue
        hits.append((v,s,e))
    if not hits:return "INSUFFICIENT",None
    v,_s,e=max(hits,key=lambda t:(t[1],t[2]["overlap"],t[2]["similarity"]))
    e["selected"]=True
    return v,e
@app.post("/v1/verify")
def verify(r:ClaimRequest):
    import torch
    try:
        enc,idx,meta,nt,nli=load_fever(); par=read_json(MODELS/"Fever/truthlens_fever_iteration2/experiment_report_v2.json").get("best_parameters",{}); ms=float(par.get("min_similarity",.55)); mo=float(par.get("min_overlap",.3)); mn=float(par.get("min_nli",.8)); mg=float(par.get("min_margin",.2)); ma=float(par.get("min_assertion_overlap",.5)); LM=nli_label_map(nli)
        if not all(k in LM for k in ("entailment","contradiction","neutral")):raise RuntimeError("NLI label mapping incomplete")
        q=enc.encode([r.claim],convert_to_numpy=True,normalize_embeddings=True).astype(np.float32); D,I=idx.search(q,min(10,idx.ntotal)); cand=[]
        for rank,(sim,i) in enumerate(zip(D[0],I[0]),start=1):
            if i<0:continue
            row=meta.iloc[int(i)]; title=clean_wiki_text(str(row.page_id).replace("_"," ")); txt=str(row.evidence_text); cand.append({"page_id":str(row.page_id),"sentence_id":int(row.sentence_id),"page_title":title,"text":txt,"premise":title+". "+txt,"similarity":float(sim),"rank":rank})
        ev=[]
        if cand:
            for c in cand:c["overlap"]=lexical_overlap(r.claim,c["premise"])
            todo=[c for c in cand if pre_nli_reject(c,ms,mo) is None] if nli_prefilter_on() else cand
            by_id={}
            if todo:
                batch=nt([c["premise"] for c in todo],[r.claim]*len(todo),padding=True,truncation=True,max_length=256,return_tensors="pt"); batch={k:v.to(dev()) for k,v in batch.items()}
                with torch.inference_mode():P=torch.softmax(nli(**batch).logits,-1).cpu().tolist()
                by_id={id(c):p for c,p in zip(todo,P)}
            for c in cand:
                p=by_id.get(id(c))
                if p is None:c["named_scores"]=None;c["nli_scores"]=[];c["nli_labels"]=[]
                else:
                    ent,con,neu=float(p[LM["entailment"]]),float(p[LM["contradiction"]]),float(p[LM["neutral"]]);c["named_scores"]={"entailment":ent,"contradiction":con,"neutral":neu};c["nli_scores"]=[ent,con,neu];c["nli_labels"]=["ENTAILMENT","CONTRADICTION","NEUTRAL"]
                c["selected"]=False; c["rejected"]=None; ev.append(c)
        verdict,selected=gate_and_select(r.claim,ev,ms,mo,mn,mg,ma)
        if selected:
            signal="entailment" if verdict=="SUPPORTED" else "contradiction"
            reason=f"{verdict}: strongest {signal} signal {selected['named_scores'][signal]:.3f} from {selected['page_id']}#{selected['sentence_id']} in the indexed repository."
        else:reason="No conclusive evidence found in the indexed repository."
        for e in ev:e.pop("premise",None)
        rel=related_passages_for(r.claim,q,cand)
        return {"verdict":verdict,"claim":r.claim,"reason":reason,"selected":({"page_id":selected["page_id"],"sentence_id":selected["sentence_id"]} if selected else None),"evidence":ev,"parameters":{**par,"min_assertion_overlap":ma},"model":"FEVER Iteration 2","note":"Local corpus only (107,525 sentences from 12,408 pages); not live web search. Entailment/contradiction/neutral are NLI model probabilities, not calibrated truth probabilities. Passages that do not address the claim's subject (and, for refutations, its assertion) are reported as inconclusive, never as evidence.",**({"related_passages":rel} if rel else {})}
    except Exception as e:raise HTTPException(503,{"code":"FEVER_UNAVAILABLE","message":str(e)})
def load_face():
    with _lock:
        if "face" in _loaded:return _loaded["face"]
        import torch,torchvision.models as models
        c=read_json(MODELS/"FFC++/truthlens_faceforensics_model/model_config.json"); m=models.efficientnet_b0(weights=None); m.classifier[1]=torch.nn.Linear(m.classifier[1].in_features,2); x=torch.load(MODELS/"FFC++/truthlens_faceforensics_model/efficientnet_b0_improved.pt",map_location=dev(),weights_only=False); m.load_state_dict(x["model_state_dict"]); m.to(dev()).eval(); _loaded["face"]=(m,c); return m,c
MAX_IMAGE_BYTES=10*1024*1024; MAX_PIXELS=40_000_000; _cam_lock=threading.Lock()
def validate_image_upload(content_type:str,data:bytes):
    if content_type not in {"image/jpeg","image/png"}:raise HTTPException(415,"Only JPG, JPEG, and PNG are accepted")
    if len(data)>MAX_IMAGE_BYTES:raise HTTPException(413,"Image exceeds the 10 MB limit")
    from PIL import Image
    try:im=Image.open(io.BytesIO(data))
    except Exception:raise HTTPException(400,"The file is not a readable image")
    if im.width*im.height>MAX_PIXELS:raise HTTPException(413,"Image decoded size exceeds the allowed limit")
    return im.convert("RGB")
@app.post("/v1/classify/image")
async def classify_image(file:UploadFile=File(...)):
    data=await file.read()
    try:
        import torch; from torchvision.transforms import Compose,Resize,ToTensor,Normalize
        m,c=load_face(); im=validate_image_upload(file.content_type,data); x=Compose([Resize((c.get("image_size",224),c.get("image_size",224))),ToTensor(),Normalize(c["mean"],c["std"] )])(im).unsqueeze(0).to(dev())
        with torch.inference_mode():p=torch.softmax(m(x)/float(c["temperature"]),-1)[0].cpu().tolist()
        manip=float(p[1]); exported_margin=float(c["uncertain_margin"]); margin,overridden=deployment_float("TRUTHLENS_IMAGE_UNCERTAIN_MARGIN",exported_margin); verdict,binary,confidence=faceforensics_verdict(manip,float(c["binary_threshold"]),margin); return {"verdict":verdict,"class_explained":binary,"probabilities":{"AUTHENTIC":float(p[0]),"MANIPULATED":manip},"confidence":confidence,"model":"FaceForensics++ EfficientNet-B0","config":c,"deployment_overrides":{"image_uncertain_margin":margin,"source":"deployment_override" if overridden else "exported_model_config","exported_model_margin":exported_margin,"binary_threshold":float(c["binary_threshold"]),"temperature":float(c["temperature"]),"preprocessing":{"image_size":c.get("image_size"),"mean":c.get("mean"),"std":c.get("std")}}}
    except Exception as e:raise HTTPException(503,{"code":"IMAGE_MODEL_UNAVAILABLE","message":str(e)})
@app.post("/v1/explanations/shap")
def shap_explanation(r:TextRequest):
    c=read_json(MODELS/"Welfake/results/truthlens_text_model_config.json"); n=int(c.get("max_length",256))
    tok,model=load_welfake(); raw=f"{r.title.strip()} {r.body.strip()}".strip()
    full=tok(raw,truncation=False,add_special_tokens=True); truncated=len(full["input_ids"])>n
    processed=tok.decode(tok(raw,truncation=True,max_length=n,add_special_tokens=True)["input_ids"],skip_special_tokens=True)
    words=processed.split()
    text_truncated=len(words)>64
    if text_truncated: processed=" ".join(words[:64])
    try:
        import shap, torch
        def predict(texts):
            z=tok(list(texts),truncation=True,max_length=n,padding=True,return_tensors="pt")
            z={k:v.to(dev()) for k,v in z.items() if k in {"input_ids","attention_mask"}}
            with torch.inference_mode(): return torch.softmax(model(**z).logits,-1).detach().cpu().numpy()
        explainer=shap.Explainer(predict, shap.maskers.Text(tok), output_names=["REAL","FAKE"])
        max_evals,evals_overridden=shap_budget(); sv=explainer([processed], max_evals=max_evals)
        tokens=[]
        alignment="unverified"
        mask_tokens=list(sv.data[0])
        values=sv.values[0]
        try:
            # The Text masker slices the explained string with this tokenizer's
            # offsets, so re-tokenizing the same string verifies alignment.
            probe=tok(processed,return_offsets_mapping=True,add_special_tokens=True)
            offsets=[(0,0) if o is None else (int(o[0]),int(o[1])) for o in probe["offset_mapping"]]
            if len(offsets)==len(mask_tokens):
                alignment="verified"
                for idx,(token,(start,end),value) in enumerate(zip(mask_tokens,offsets,values)):
                    # The masker's segment spans from this token's start to the
                    # next token's start, so mirror that span exactly.
                    span_end=max(end,offsets[idx+1][0]) if idx+1<len(offsets) else end
                    real=float(value[0]) if getattr(value,"__len__",None) else float(value)
                    fake=float(value[1]) if getattr(value,"__len__",None) and len(value)>1 else -real
                    direction="supports REAL" if real>1e-8 else ("supports FAKE" if real<-1e-8 else "neutral")
                    tokens.append({"token":str(token),"start":start,"end":span_end,"real_contribution":real,"fake_contribution":fake,"direction":direction})
        except Exception:
            tokens=[]
        return {"method":"SHAP","status":"generated","target":"REAL probability","output_names":["REAL","FAKE"],"alignment":alignment,"truncated":truncated,"text_truncated":text_truncated,"max_evals":max_evals,"max_evals_source":"deployment_override" if evals_overridden else "default","explained_text":processed,"tokens":tokens,"note":"Explanation covers only text processed by the model. Truncation is disclosed when it occurs. Token influence is not factual truth."}
    except Exception as e:
        return {"method":"SHAP","status":"unavailable","truncated":truncated,"explained_text":processed,"tokens":[],"alignment":"unverified","error":str(e),"note":"Explanation covers only text processed by the model and is not reused from saved example files."}
@app.post("/v1/explanations/gradcam")
async def gradcam(file:UploadFile=File(...)):
    data=await file.read()
    try:
        import torch; from torchvision.transforms import Compose,Resize,ToTensor,Normalize
        m,c=load_face(); im=validate_image_upload(file.content_type,data)
        x=Compose([Resize((c.get("image_size",224),c.get("image_size",224))),ToTensor(),Normalize(c["mean"],c["std"] )])(im).unsqueeze(0).to(dev())
        # Target the threshold-selected binary class (same rule as classify_image),
        # shown independently of any UNCERTAIN verdict.
        manip=float(torch.softmax(m(x)/float(c["temperature"]),-1)[0,1].detach().cpu()); target=1 if manip>=float(c["binary_threshold"]) else 0
        with _cam_lock:
            acts=[];grads=[]; layer=m.features[-1]
            h1=layer.register_forward_hook(lambda _,__,o:acts.append(o)); h2=layer.register_full_backward_hook(lambda _,__,go:grads.append(go[0]))
            try:
                m.zero_grad(set_to_none=True); y=m(x); y[0,target].backward()
            finally:
                h1.remove(); h2.remove()
        a,g=acts[0],grads[0]; heat=torch.relu((g.mean((2,3),keepdim=True)*a).sum(1))[0]; heat=(heat-heat.min())/(heat.max()-heat.min()+1e-8); return {"status":"generated","class_explained":"MANIPULATED" if target else "AUTHENTIC","heatmap_values":heat.detach().cpu().numpy().round(4).tolist(),"note":"Grad-CAM shows regions influencing the threshold-selected binary class for this upload; it is model influence, not a confirmed manipulation mask."}
    except HTTPException as e:raise e
    except Exception as e:raise HTTPException(503,{"code":"GRADCAM_UNAVAILABLE","message":str(e)})
