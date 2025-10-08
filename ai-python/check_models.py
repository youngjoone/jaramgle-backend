import os
import google.generativeai as genai

def check_available_models():
    """현재 API 키로 사용 가능한 모든 Gemini 모델 목록을 조회하고 출력합니다."""
    print("--- 사용 가능한 Gemini 모델 목록 조회 시작 ---")
    
    try:
        # 1. API 키 설정
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            print("오류: GEMINI_API_KEY 환경 변수를 설정해주세요.")
            return

        genai.configure(api_key=api_key)

        # 2. 모델 목록 조회 및 출력
        print("\n[지원되는 모델 목록]")
        for model in genai.list_models():
            # 'generateContent' (채팅/텍스트 생성)을 지원하는 모델만 필터링
            if 'generateContent' in model.supported_generation_methods:
                print(f"- 모델명: {model.name}")
                # print(f"  - 설명: {model.description}")
                # print(f"  - 지원하는 메소드: {model.supported_generation_methods}")

    except Exception as e:
        print(f"\n모델 목록 조회 중 오류 발생: {e}")

    print("\n--- 조회 종료 ---")

if __name__ == "__main__":
    check_available_models()

