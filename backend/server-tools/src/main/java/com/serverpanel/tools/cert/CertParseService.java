package com.serverpanel.tools.cert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.tools.cert.dto.CertOptionsVO;
import com.serverpanel.tools.cert.dto.CertParseBody;
import com.serverpanel.tools.cert.dto.CertParseResultVO;
import com.serverpanel.tools.cert.dto.CertTypeVO;
import com.serverpanel.tools.service.RegionLookupService;
import org.springframework.stereotype.Service;

/**
 * 证件解析分发服务。
 *
 * <p>收集 Spring 容器内全部 {@link CertParser} 实现（每种证件一个策略 bean），
 * options 一次性下发类型清单 + 构造规则；parse 按类型分发。
 * 证件号原文不写日志（隐私红线，调用方 Controller 侧 recordParams=false）。
 *
 * @author zhaodc
 * @since 2026-09-25 UTC+8
 */
@Service
public class CertParseService {

    /** 输入长度上限 */
    private static final int MAX_VALUE_LENGTH = 64;

    private final Map<String, CertParser> parsers;

    private final RegionLookupService regionLookup;

    public CertParseService(List<CertParser> parserBeans, RegionLookupService regionLookup) {
        Map<String, CertParser> map = new LinkedHashMap<>();
        for (CertParser parser : parserBeans) {
            map.put(parser.type().getKey(), parser);
        }
        this.parsers = Map.copyOf(map);
        this.regionLookup = regionLookup;
    }

    /**
     * 可选清单：7 种证件的类型元信息 + 构造规则 + 上限/数据就绪状态。
     */
    public CertOptionsVO options() {
        List<CertTypeVO> types = new ArrayList<>();
        for (CertType certType : CertType.values()) {
            CertParser parser = parsers.get(certType.getKey());
            types.add(new CertTypeVO(certType.getKey(), certType.getName(), certType.getIcon(),
                    certType.isNeedRegion(), certType.getPlaceholder(),
                    parser == null ? List.of() : List.of(certType.getSample()),
                    parser == null ? List.of() : parser.rules()));
        }
        return new CertOptionsVO(types,
                new CertOptionsVO.Limits(MAX_VALUE_LENGTH, regionLookup.ready()));
    }

    /**
     * 解析证件号码。
     *
     * <p>结构/校验失败不抛异常（valid=false 返回业务结论）；
     * 只有类型不支持、输入为空/超长这类「请求本身不合法」才走错误码。
     */
    public CertParseResultVO parse(CertParseBody body) {
        CertType certType = CertType.of(body.type());
        if (certType == null) {
            throw new ServiceException(ErrorCode.TOOLS_CERT_TYPE_UNSUPPORTED);
        }
        String value = body.value() == null ? "" : body.value().trim();
        if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            throw new ServiceException(ErrorCode.TOOLS_CERT_VALUE_INVALID);
        }
        CertParser parser = parsers.get(certType.getKey());
        if (parser == null) {
            throw new ServiceException(ErrorCode.TOOLS_CERT_TYPE_UNSUPPORTED);
        }
        return parser.parse(value);
    }
}
