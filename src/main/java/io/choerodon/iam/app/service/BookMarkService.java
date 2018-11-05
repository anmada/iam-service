package io.choerodon.iam.app.service;

import io.choerodon.iam.api.dto.BookMarkDTO;

import java.util.List;

/**
 * @author dengyouquan
 **/
public interface BookMarkService {
    BookMarkDTO create(BookMarkDTO bookMarkDTO);

    /**
     * 更新传入书签列表
     * @param bookMarkDTOS
     * @return
     */
    List<BookMarkDTO> updateAll(List<BookMarkDTO> bookMarkDTOS);

    /**
     * 查询所有书签
     * @return
     */
    List<BookMarkDTO> queryAll();

    void delete(Long id);
}