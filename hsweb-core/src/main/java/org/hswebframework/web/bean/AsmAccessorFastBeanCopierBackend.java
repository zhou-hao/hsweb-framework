package org.hswebframework.web.bean;

import org.hswebframework.web.bean.accessor.AsmBeanAccessor;

final class AsmAccessorFastBeanCopierBackend extends AccessorFastBeanCopierBackend {

    AsmAccessorFastBeanCopierBackend() {
        super(new AsmBeanAccessor());
    }
}
