package com.adoptpet.server.community.service;

import com.adoptpet.server.commons.exception.CustomException;
import com.adoptpet.server.commons.image.dto.ImageInfoDto;
import com.adoptpet.server.commons.util.SecurityUtils;
import com.adoptpet.server.community.domain.Category;
import com.adoptpet.server.community.domain.Community;
import com.adoptpet.server.community.domain.LogicalDelEnum;
import com.adoptpet.server.community.dto.*;
import com.adoptpet.server.community.repository.CategoryRepository;
import com.adoptpet.server.community.repository.CommunityImageRepository;
import com.adoptpet.server.community.repository.CommunityQDslRepository;
import com.adoptpet.server.community.service.mapper.CreateArticleMapper;
import com.adoptpet.server.community.repository.CommunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

import static com.adoptpet.server.commons.exception.ErrorCode.*;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommunityService {

    private final CommunityRepository communityRepository;
    private final CommunityQDslRepository communityQDslRepository;
    private final CommunityImageRepository communityImageRepository;
    private final CategoryRepository categoryRepository;

    /**
    * 게시글 목록 조회
    **/
    @Transactional
    public List<ArticleListDto> readArticleList(String order, Integer pageNum, Integer option, String keyword){

        List<ArticleListDto> articleList = communityQDslRepository.selectArticleList(order,pageNum,option,keyword);

        return articleList;
    }

    /**
    * 게시글 목록의 인기글(HOT,WEEKLY) 조회
    **/
    @Transactional
    public Map<String,ArticleListDto> getTrendingArticleDayAndWeekly(){

        final LocalDateTime endAt = LocalDateTime.now();
        final LocalDateTime startAtOfDay = endAt.minusDays(1);
        final LocalDateTime startAtOfWeekly = endAt.minusWeeks(1);
        final Integer limit = 2;

        List<TrendingArticleDto> articleOfDay
                = communityQDslRepository.findTrendingArticle(startAtOfDay, endAt, limit);
        List<TrendingArticleDto> articleOfWeekly
                = communityQDslRepository.findTrendingArticle(startAtOfWeekly, endAt, limit);

        for(TrendingArticleDto dto :articleOfWeekly){
            log.info("weekly"+1+"  {} , {}", dto.getArticleNo(),dto.getLikeCnt());
        }

        final TrendingArticleDto trendingDay = articleOfDay.get(0);
        TrendingArticleDto trendingWeekly = articleOfWeekly.get(0);

        if(trendingDay.getLikeCnt() >= trendingWeekly.getLikeCnt()){
            trendingWeekly = articleOfWeekly.get(1);
        }

        Map<String,ArticleListDto> result = new HashMap<>();
        ArticleListDto articleDataOfDay = communityQDslRepository.findArticleOneForList(trendingDay.getArticleNo());
        ArticleListDto articleDataOfWeekly = communityQDslRepository.findArticleOneForList(trendingWeekly.getArticleNo());

        result.put("day",articleDataOfDay);
        result.put("weekly",articleDataOfWeekly);

        return result;
    }




    /**
    * 게시글 유무 검증
    **/
    @Transactional(readOnly = true)
    public Community findByArticleNo(Integer articleNo){
        // 게시글 번호로 게시글을 조회하고, 조회되지 않을 경우 예외를 발생시킨다.
        Optional<Community> findCommunity = communityRepository.findById(articleNo);
        if(!findCommunity.isPresent()){
            throw new CustomException(ARTICLE_NOT_FOUND);
        }

        // 삭제된 게시글 조회 검증
        if(!findCommunity.get().getLogicalDel().equals(LogicalDelEnum.NORMAL)){
            throw new CustomException(DELETED_ARTICLE_BAD_REQUEST);
        }

        return findCommunity.get();
    }

    //== 카테고리 유무 검증 ==//
    @Transactional(readOnly = true)
    public void checkCategoryNo(Integer categoryNo) {
        Optional<Category> findCategory = categoryRepository.findById(categoryNo);
        if(!findCategory.isPresent()){
            throw new CustomException(CATEGORY_NOT_FOUND);
        }
    }

    /**
    * 게시글 수정
    **/
    @Transactional
    public Community updateArticle(CommunityDto communityDto, Integer articleNo){

        // 게시글 고유키로 게시글 검증
        Community community = findByArticleNo(articleNo);

        // 게시글 데이터 엔티티 저장
        community.updateArticleByMod(communityDto, SecurityUtils.getUser().getEmail());

        // 카테고리 번호 검증
        checkCategoryNo(community.getCategoryNo());

        // 이미지 업데이트
        updateImageByArticleNo(communityDto.getImage(),articleNo);

        // 게시글 업데이트
        Community updatedArticle = communityRepository.save(community);

        return updatedArticle;
    }


    /**
    * 게시글 상세 내용 조회
    **/
    @Transactional(readOnly = true)
    public ArticleDetailInfoDto readArticle(Integer articleNo, String accessToken){
        // 게시글 고유키로 게시글 검증
        findByArticleNo(articleNo);

        // 게시글 정보 조회
        ArticleDetailInfoDto articleDetail = communityQDslRepository.findArticleDetail(articleNo);

        // 조회 유저 검증 기본 값 지정
        articleDetail.addIsMine(false);

        // 엑세스 토큰 있을 시 조회한 유저가 게시글의 주인인지 확인
        if (StringUtils.hasText(accessToken)) {
            // 현재 게시글을 보려는 회원이 이 게시글을 작성한 작성자와 같은지 확인한다.
            boolean isMine = communityQDslRepository.isMine(SecurityUtils.getUser().getEmail(), articleNo);
            articleDetail.addIsMine(isMine);
        }

        // 이미지 URL 조회 -> 없을 시 null 반환
        List<ImageInfoDto> images = communityQDslRepository.findImageUrlByArticleNo(articleNo);

        // 이미지 URL 리스트 추가
        articleDetail.addImages(images);

        // 조회된 게시글 정보 반환
        return articleDetail;
    }


    /**
    * 게시글 등록 메서드
     *  @param communityDto  : 게시글 생성 정보
     *  @return response     : 생성 완료된 게시글 정보
    **/
    @Transactional
    public CommunityDto insertArticle(CommunityDto communityDto){
        // CreateArticleMapper 인스턴스 생성
        final CreateArticleMapper createArticleMapper = CreateArticleMapper.INSTANCE;

        // DTO를 Entity로 매핑
        Community community = createArticleMapper.toEntity(communityDto);

        // 카테고리 번호 검증
        checkCategoryNo(community.getCategoryNo());

        // Community DB 저장
        Community saveArticle = communityRepository.save(community);

        // 이미지 업데이트
        ArticleImageDto[] images = communityDto.getImage();
        updateImageByArticleNo(communityDto.getImage(), saveArticle.getArticleNo());

        // 저장된 Community를 DTO로 변환
        CommunityDto response = createArticleMapper.toDTO(saveArticle);

        // 반환값에 null 대신 image 기입
        response.addImgNo(images);

        return response;
    }




    @Transactional
    public void updateImageByArticleNo(ArticleImageDto[] imageNoArr, Integer articleNo) {
        if (!Objects.isNull(imageNoArr)) {
            // 커뮤니티 이미지 테이블에 커뮤니티 게시글 고유키와 정렬 기준을 업데이트
            for (int i=0; i<imageNoArr.length; i++) {
                communityImageRepository.updateImagByArticleNo(articleNo, imageNoArr[i].getImageNo(), (i+1));
            }
        } else {
            communityImageRepository.updateAllByArticleNo(articleNo);
        }
    }
    
    
    /**
     * 게시글 논리 삭제
     */
    @Transactional
    public Community softDeleteArticle(Integer articleNo) {

        // 게시글 번호를 사용하여 해당 게시글을 조회한다.
        Community community = findByArticleNo(articleNo);
        // 게시글을 논리 삭제한다.
        community.deleteByLogicalDel(LogicalDelEnum.DELETE);
        // 게시글과 관련된 데이터인 북마크와 좋아요를 삭제한다.
        communityQDslRepository.deleteBookmark(community);
        communityQDslRepository.deleteArticleLike(community);
        // 게시글 이미지의 게시글 번호 컬럼을 비운다.(batch를 통해 자정에 삭제)
        communityImageRepository.updateAllByArticleNo(articleNo);
        // 변경된 게시글을 DB에 저장하여 업데이트한다.
        Community deletedCommunity = communityRepository.save(community);

        return deletedCommunity;
    }
}
