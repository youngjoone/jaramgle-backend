package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.CommentLikeStatusDto;
import com.fairylearn.backend.dto.CreateSharedStoryCommentRequest;
import com.fairylearn.backend.dto.SharedStoryCommentDto;
import com.fairylearn.backend.dto.StoryLikeStatusDto;
import com.fairylearn.backend.dto.UpdateSharedStoryCommentRequest;
import com.fairylearn.backend.entity.SharedStory;
import com.fairylearn.backend.entity.SharedStoryComment;
import com.fairylearn.backend.entity.SharedStoryCommentLike;
import com.fairylearn.backend.entity.SharedStoryLike;
import com.fairylearn.backend.entity.User;
import com.fairylearn.backend.repository.SharedStoryCommentLikeRepository;
import com.fairylearn.backend.repository.SharedStoryCommentRepository;
import com.fairylearn.backend.repository.SharedStoryLikeRepository;
import com.fairylearn.backend.repository.SharedStoryRepository;
import com.fairylearn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SharedStoryInteractionService {

    private static final String DELETED_COMMENT_MESSAGE = "삭제된 댓글입니다.";

    private final SharedStoryRepository sharedStoryRepository;
    private final SharedStoryLikeRepository sharedStoryLikeRepository;
    private final SharedStoryCommentRepository sharedStoryCommentRepository;
    private final SharedStoryCommentLikeRepository sharedStoryCommentLikeRepository;
    private final UserRepository userRepository;

    @Transactional
    public StoryLikeStatusDto toggleStoryLike(String shareSlug, Long userId) {
        SharedStory sharedStory = getSharedStoryBySlug(shareSlug);
        User user = getUserById(userId);

        Optional<SharedStoryLike> existing = sharedStoryLikeRepository.findBySharedStory_IdAndUser_Id(sharedStory.getId(), userId);
        boolean liked;
        if (existing.isPresent()) {
            sharedStoryLikeRepository.delete(existing.get());
            liked = false;
        } else {
            SharedStoryLike like = new SharedStoryLike();
            like.setSharedStory(sharedStory);
            like.setUser(user);
            sharedStoryLikeRepository.save(like);
            liked = true;
        }
        long likeCount = sharedStoryLikeRepository.countBySharedStory_Id(sharedStory.getId());
        return new StoryLikeStatusDto(likeCount, liked);
    }

    @Transactional(readOnly = true)
    public List<SharedStoryCommentDto> getComments(String shareSlug, Long currentUserId) {
        SharedStory sharedStory = getSharedStoryBySlug(shareSlug);
        List<SharedStoryComment> comments = sharedStoryCommentRepository.findBySharedStory_IdOrderByCreatedAtAsc(sharedStory.getId());
        return mapCommentsToTree(comments, currentUserId);
    }

    @Transactional
    public SharedStoryCommentDto createComment(String shareSlug, Long userId, CreateSharedStoryCommentRequest request) {
        log.info("[CommentService] create slug={}, user={}", shareSlug, userId);
        System.out.println("[CommentService] create called slug=" + shareSlug + " user=" + userId);
        SharedStory sharedStory = getSharedStoryBySlug(shareSlug);
        User user = getUserById(userId);

        String rawContent = request.content();
        if (rawContent == null) {
            throw new IllegalArgumentException("댓글 내용을 입력해 주세요.");
        }
        String content = rawContent.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("댓글 내용을 입력해 주세요.");
        }

        SharedStoryComment comment = new SharedStoryComment();
        comment.setSharedStory(sharedStory);
        comment.setUser(user);
        comment.setContent(content);
        comment.setDeleted(false);

        if (request.parentCommentId() != null) {
            SharedStoryComment parent = sharedStoryCommentRepository.findByIdAndSharedStory_Id(request.parentCommentId(), sharedStory.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Parent comment not found"));
            comment.setParent(parent);
        }

        try {
            SharedStoryComment saved = sharedStoryCommentRepository.save(comment);
            sharedStoryCommentRepository.flush();
            return mapCommentToDto(saved, 0L, false, userId);
        } catch (Exception ex) {
            log.error("Failed to save comment for shareSlug={}, userId={}, content={}", shareSlug, userId, content, ex);
            throw new IllegalStateException("댓글을 저장하는 동안 오류가 발생했습니다.", ex);
        }
    }

    @Transactional
    public SharedStoryCommentDto updateComment(Long commentId, Long userId, UpdateSharedStoryCommentRequest request) {
        SharedStoryComment comment = sharedStoryCommentRepository.findByIdAndUser_Id(commentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found or not owned by user"));

        if (comment.isDeleted()) {
            throw new IllegalStateException("삭제된 댓글은 수정할 수 없습니다.");
        }

        String rawContent = request.content();
        if (rawContent == null) {
            throw new IllegalArgumentException("댓글 내용을 입력해 주세요.");
        }
        String content = rawContent.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("댓글 내용을 입력해 주세요.");
        }

        comment.setContent(content);
        SharedStoryComment saved = sharedStoryCommentRepository.save(comment);

        long likeCount = sharedStoryCommentLikeRepository.countByComment_Id(commentId);
        boolean liked = sharedStoryCommentLikeRepository.existsByComment_IdAndUser_Id(commentId, userId);
        return mapCommentToDto(saved, likeCount, liked, userId);
    }

    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        SharedStoryComment comment = sharedStoryCommentRepository.findByIdAndUser_Id(commentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found or not owned by user"));

        comment.setDeleted(true);
        comment.setContent(DELETED_COMMENT_MESSAGE);
        sharedStoryCommentRepository.save(comment);
    }

    @Transactional
    public CommentLikeStatusDto toggleCommentLike(Long commentId, Long userId) {
        SharedStoryComment comment = sharedStoryCommentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found"));
        User user = getUserById(userId);

        Optional<SharedStoryCommentLike> existing = sharedStoryCommentLikeRepository.findByComment_IdAndUser_Id(commentId, userId);
        boolean liked;
        if (existing.isPresent()) {
            sharedStoryCommentLikeRepository.delete(existing.get());
            liked = false;
        } else {
            SharedStoryCommentLike like = new SharedStoryCommentLike();
            like.setComment(comment);
            like.setUser(user);
            sharedStoryCommentLikeRepository.save(like);
            liked = true;
        }
        long likeCount = sharedStoryCommentLikeRepository.countByComment_Id(commentId);
        return new CommentLikeStatusDto(commentId, likeCount, liked);
    }

    private SharedStory getSharedStoryBySlug(String shareSlug) {
        return sharedStoryRepository.findByShareSlug(shareSlug)
                .orElseThrow(() -> new IllegalArgumentException("Shared story not found"));
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private List<SharedStoryCommentDto> mapCommentsToTree(List<SharedStoryComment> comments, Long currentUserId) {
        if (comments.isEmpty()) {
            return List.of();
        }

        List<Long> commentIds = comments.stream().map(SharedStoryComment::getId).toList();
        Map<Long, Long> likeCounts = aggregateLikeCounts(commentIds);
        Set<Long> likedIds = currentUserId != null ? new HashSet<>(sharedStoryCommentLikeRepository.findLikedCommentIdsByUser(commentIds, currentUserId)) : Set.of();

        Map<Long, SharedStoryCommentDto> dtoIndex = new LinkedHashMap<>();
        List<SharedStoryCommentDto> roots = new ArrayList<>();

        for (SharedStoryComment comment : comments) {
            long likeCount = likeCounts.getOrDefault(comment.getId(), 0L);
            boolean liked = currentUserId != null && likedIds.contains(comment.getId());
            SharedStoryCommentDto dto = mapCommentToDto(comment, likeCount, liked, currentUserId);
            dtoIndex.put(comment.getId(), dto);

            SharedStoryComment parent = comment.getParent();
            if (parent != null) {
                SharedStoryCommentDto parentDto = dtoIndex.get(parent.getId());
                if (parentDto != null) {
                    parentDto.getReplies().add(dto);
                } else {
                    roots.add(dto);
                }
            } else {
                roots.add(dto);
            }
        }
        return roots;
    }

    private Map<Long, Long> aggregateLikeCounts(List<Long> commentIds) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : sharedStoryCommentLikeRepository.countByCommentIds(commentIds)) {
            Long commentId = (Long) row[0];
            Long count = (Long) row[1];
            result.put(commentId, count);
        }
        return result;
    }

    private SharedStoryCommentDto mapCommentToDto(SharedStoryComment comment, long likeCount, boolean liked, Long currentUserId) {
        boolean editable = !comment.isDeleted() && currentUserId != null && comment.getUser().getId().equals(currentUserId);
        String content = comment.isDeleted() ? DELETED_COMMENT_MESSAGE : comment.getContent();
        Long parentId = comment.getParent() != null ? comment.getParent().getId() : null;
        return SharedStoryCommentDto.builder()
                .id(comment.getId())
                .parentId(parentId)
                .authorId(comment.getUser().getId())
                .authorNickname(comment.getUser().getName())
                .editable(editable)
                .deleted(comment.isDeleted())
                .content(content)
                .likeCount(likeCount)
                .likedByCurrentUser(liked)
                .createdAt(comment.getCreatedAt())
                .build();
    }
}
