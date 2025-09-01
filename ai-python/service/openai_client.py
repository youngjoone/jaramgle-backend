# service/openai_client.py
from openai import OpenAI
from schemas import GenerateRequest, GenerateResponse, Moderation

class OpenAIClient:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)

    def build_prompt(self, req: GenerateRequest) -> list:
        traits = (req.profile.traits or {}).__dict__ if hasattr(req.profile, "traits") else {}
        mood_tags = ", ".join(req.mood.tags or [])
        intensity = req.mood.intensity or 50

        system = (
            "당신은 한국어 자유시를 쓰는 시인입니다. 독자의 기분과 성향을 섬세하게 반영하되, "
            "의학적/임상적 진단이나 조언은 하지 않습니다. 10행 이내, 간결하고 진정성 있게."
        )
        user = (
            f"[프로필 traits] {traits}\n"
            f"[현재 기분] tags={mood_tags}, intensity={intensity}\n"
            "위 정보를 반영하여 짧은 자유시를 한 편 써줘."
        )
        return [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]

    def generate_text(self, req: GenerateRequest, request_id: str) -> GenerateResponse:
        messages = self.build_prompt(req)

        # 가벼운 모델(예: gpt-4o-mini) 권장: 비용/지연 대비 품질 균형
        resp = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=messages,
            temperature=0.9,
            max_tokens=300,
            user=request_id or "anon"  # 추적용 user 파라미터
        )
        poem = resp.choices[0].message.content.strip()

        # 간단 모더레이션 플래그(고급은 moderation 엔드포인트/휴먼오버사이트 고려)
        moderation = Moderation(safe=True, flags=[])
        return GenerateResponse(poem=poem, img_prompt=None, moderation=moderation)

