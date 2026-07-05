# BGM Library

Royalty-free background music for storybook videos.
Source: [Fesliyanstudios.com](https://www.fesliyanstudios.com) — free for use, donation appreciated for commercial projects.

## Tracks

| File | Mood (KO) | Original Title | Duration |
|------|-----------|----------------|----------|
| `warm.mp3` | 따뜻하고 포근한 | Childhood Nostalgia | ~3:00 |
| `magical.mp3` | 신비롭고 마법같은 | Morning Magic | ~2:28 |
| `adventure.mp3` | 신나는 모험 | Forest Ventures | ~3:19 |
| `peaceful.mp3` | 잔잔하고 평화로운 | Beautiful Village | ~3:09 |
| `cheerful.mp3` | 밝고 유쾌한 | Tiny Kingdom | ~2:18 |
| `mysterious.mp3` | 으스스하고 신비로운 | Elven Forest | ~4:22 |
| `dreamy.mp3` | 몽환적이고 꿈같은 | Dreams of a Child | ~2:51 |

## Mood Mapping

Story JSON에 `"mood"` 필드를 추가하면 자동 선택됩니다:

```json
{
  "title": "토끼와 거북이",
  "mood": "cheerful",
  "pages": [...]
}
```

### 분위기 가이드

| mood 값 | 어울리는 이야기 유형 |
|---------|-----------------|
| `warm` | 가족 이야기, 우정, 감동적인 결말 |
| `magical` | 마법사, 요정, 신비한 세계 |
| `adventure` | 탐험, 모험, 용감한 주인공 |
| `peaceful` | 자연 속 이야기, 잔잔한 일상 |
| `cheerful` | 유머러스, 동물 친구들, 밝은 분위기 |
| `mysterious` | 수수께끼, 숲속 비밀, 살짝 긴장감 |
| `dreamy` | 잠자리 동화, 꿈 이야기, 조용한 마무리 |

## BGM 혼합 볼륨

BGM은 나레이션 음성을 방해하지 않도록 **약 10~15% 볼륨**으로 혼합됩니다.
`story_video.py --bgm-volume 0.12` 로 조절 가능합니다.
