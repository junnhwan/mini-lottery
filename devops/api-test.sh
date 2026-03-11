#!/bin/bash
# ============================================================
# Mini-Lottery 接口测试脚本
# 使用方式：直接复制单条 curl 命令到终端执行，或 bash api-test.sh 全部执行
# 测试数据：A20260310001(日常,3次上限), A20260310002(压测,不限次数), A20260310003(已结束)
# ============================================================

BASE_URL="http://localhost:8080/api/lottery"

echo "=============================="
echo "1. 正常抽奖（POST）"
echo "=============================="
curl -s -X POST "${BASE_URL}/draw?userId=user001&activityId=A20260310002" | python -m json.tool
echo ""

echo "=============================="
echo "2. 查询抽奖记录（GET）"
echo "=============================="
curl -s "${BASE_URL}/records?userId=user001&activityId=A20260310002" | python -m json.tool
echo ""

echo "=============================="
echo "3. 活动不存在 → code=1001"
echo "=============================="
curl -s -X POST "${BASE_URL}/draw?userId=user001&activityId=NOT_EXIST" | python -m json.tool
echo ""

echo "=============================="
echo "4. 活动已结束 → code=1002"
echo "=============================="
curl -s -X POST "${BASE_URL}/draw?userId=user001&activityId=A20260310003" | python -m json.tool
echo ""

echo "=============================="
echo "5. 超过参与次数上限 → code=1004"
echo "   （用日常活动，上限 3 次，先抽 3 次再测第 4 次）"
echo "=============================="
curl -s -X POST "${BASE_URL}/draw?userId=testLimit&activityId=A20260310001" > /dev/null
curl -s -X POST "${BASE_URL}/draw?userId=testLimit&activityId=A20260310001" > /dev/null
curl -s -X POST "${BASE_URL}/draw?userId=testLimit&activityId=A20260310001" > /dev/null
curl -s -X POST "${BASE_URL}/draw?userId=testLimit&activityId=A20260310001" | python -m json.tool
echo ""

echo "=============================="
echo "6. 限流测试 → code=1006"
echo "   （60 秒内连续发 6 次，第 6 次触发限流）"
echo "=============================="
for i in $(seq 1 5); do
  curl -s -X POST "${BASE_URL}/draw?userId=testRate&activityId=A20260310002" > /dev/null
done
curl -s -X POST "${BASE_URL}/draw?userId=testRate&activityId=A20260310002" | python -m json.tool
echo ""

echo "=============================="
echo "全部测试完成"
echo "=============================="
