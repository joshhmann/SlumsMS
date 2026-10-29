#!/bin/bash
# Final evals (chat-model wrap-up): every model on the same sets from data/chat-r7. Usage: final_eval.sh name model_dir
D=/root/slumsms-decide-benchmark/chat-classifier-2026-10-27
E=/root/OneJev/data/chat-r7
cd /root/OneJev
mkdir -p $D/final-preds-$1
args=""
for n in h70_intent h70_con b96_intent b96_con deathwish_con fresh_convo_intent fresh_con ctx_con noctx_con ctx_convo_intent noctx_convo_intent; do
  args="$args --eval $n=$E/$n.jsonl"
done
CUDA_VISIBLE_DEVICES=GPU-e22a7e7d-d13e-b2ce-8cbc-da280a63f95a /root/venvs/decide/bin/python -m train.eval_mm \
  --model "$2" --max-len 512 $args --out $D/final-$1.json --dump-preds $D/final-preds-$1 > $D/final-$1.log 2>&1
grep " acc " $D/final-$1.log | sed -E 's/^.*eval[^ ]* ([a-z0-9_]+): n ([0-9]+) acc ([0-9.]+).*/\1 \2 \3/'
