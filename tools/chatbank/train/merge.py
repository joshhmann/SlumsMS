import sys, torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel
base, ad, out = sys.argv[1:4]
m = AutoModelForCausalLM.from_pretrained(base, torch_dtype=torch.bfloat16)
m = PeftModel.from_pretrained(m, ad).merge_and_unload()
m.save_pretrained(out); AutoTokenizer.from_pretrained(ad).save_pretrained(out)
