#!/bin/bash

# ============================================
# 虎溪锐评 - Redis 哨兵模式一键部署脚本
# ============================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 显示 banner
show_banner() {
    echo -e "${BLUE}"
    echo "=========================================="
    echo "   虎溪锐评 - Redis 哨兵模式部署"
    echo "=========================================="
    echo -e "${NC}"
}

# 检查 Docker 和 Docker Compose
check_prerequisites() {
    log_info "检查系统依赖..."

    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        log_error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi

    log_success "系统依赖检查通过"
}

# 停止并清理旧容器
cleanup_old() {
    log_info "停止旧的 Redis 容器..."

    # 使用 docker-compose 或 docker compose
    if docker-compose -v &> /dev/null 2>&1; then
        docker-compose -f docker-compose.yml down --remove-orphans 2>/dev/null || true
    else
        docker compose -f docker-compose.yml down --remove-orphans 2>/dev/null || true
    fi

    log_success "旧容器已清理"
}

# 启动哨兵模式
start_sentinel() {
    log_info "启动 Redis 哨兵模式..."

    # 使用 docker-compose 或 docker compose
    if docker-compose -v &> /dev/null 2>&1; then
        docker-compose -f docker-compose-sentinel.yml up -d
    else
        docker compose -f docker-compose-sentinel.yml up -d
    fi

    log_success "哨兵模式启动中..."
}

# 等待服务就绪
wait_for_services() {
    log_info "等待服务启动..."

    local max_attempts=60
    local attempt=0

    while [ $attempt -lt $max_attempts ]; do
        # 检查主节点
        if docker exec huxirating-redis-master redis-cli -a huxirating123 ping &> /dev/null; then
            log_success "Redis 主节点已就绪"
            break
        fi

        attempt=$((attempt + 1))
        echo -n "."
        sleep 2
    done

    if [ $attempt -eq $max_attempts ]; then
        log_error "服务启动超时"
        exit 1
    fi

    # 等待哨兵就绪
    sleep 5
}

# 检查集群状态
check_cluster_status() {
    log_info "检查集群状态..."
    echo ""

    # 1. 检查主节点
    echo -e "${YELLOW}=== 主节点状态 ===${NC}"
    docker exec huxirating-redis-master redis-cli -a huxirating123 INFO replication | grep -E "role|connected_slaves|master_replid"
    echo ""

    # 2. 检查从节点
    echo -e "${YELLOW}=== 从节点 1 状态 ===${NC}"
    docker exec huxirating-redis-slave-1 redis-cli -a huxirating123 INFO replication | grep -E "role|master_host|master_link_status"
    echo ""

    echo -e "${YELLOW}=== 从节点 2 状态 ===${NC}"
    docker exec huxirating-redis-slave-2 redis-cli -a huxirating123 INFO replication | grep -E "role|master_host|master_link_status"
    echo ""

    # 3. 检查哨兵状态
    echo -e "${YELLOW}=== 哨兵状态 ===${NC}"
    docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL masters
    echo ""

    echo -e "${YELLOW}=== 哨兵监控的从节点数量 ===${NC}"
    docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL slaves mymaster | grep -c "ip"
    echo ""

    log_success "集群状态检查完成"
}

# 测试故障转移
test_failover() {
    log_warn "是否要测试故障转移？这将停止主节点！"
    read -p "输入 yes 继续，其他跳过: " confirm

    if [ "$confirm" != "yes" ]; then
        log_info "跳过故障转移测试"
        return
    fi

    log_info "停止主节点，观察故障转移..."

    # 停止主节点
    docker stop huxirating-redis-master

    # 等待故障转移
    log_info "等待哨兵选举新主节点（约 30 秒）..."
    sleep 35

    # 检查新主节点
    echo -e "${YELLOW}=== 故障转移后的主节点 ===${NC}"
    docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster

    # 恢复原主节点
    log_info "恢复原主节点..."
    docker start huxirating-redis-master

    log_success "故障转移测试完成"
}

# 显示访问信息
show_access_info() {
    echo ""
    echo -e "${GREEN}=========================================="
    echo "   部署完成！"
    echo -e "==========================================${NC}"
    echo ""
    echo "Redis 连接信息："
    echo "  主节点: localhost:6379"
    echo "  从节点 1: localhost:6380"
    echo "  从节点 2: localhost:6381"
    echo "  密码: huxirating123"
    echo ""
    echo "哨兵连接信息："
    echo "  哨兵 1: localhost:26379"
    echo "  哨兵 2: localhost:26380"
    echo "  哨兵 3: localhost:26381"
    echo ""
    echo "应用启用哨兵模式："
    echo "  修改 application.yaml:"
    echo "    spring.redis.sentinel.enabled: true"
    echo ""
    echo "查看日志："
    echo "  docker logs -f huxirating-redis-master"
    echo "  docker logs -f huxirating-redis-sentinel-1"
    echo ""
}

# 主函数
main() {
    show_banner

    # 解析命令行参数
    case "${1:-start}" in
        start)
            check_prerequisites
            cleanup_old
            start_sentinel
            wait_for_services
            check_cluster_status
            show_access_info
            ;;
        stop)
            log_info "停止哨兵模式..."
            if docker-compose -v &> /dev/null 2>&1; then
                docker-compose -f docker-compose-sentinel.yml down
            else
                docker compose -f docker-compose-sentinel.yml down
            fi
            log_success "已停止"
            ;;
        restart)
            $0 stop
            $0 start
            ;;
        status)
            check_cluster_status
            ;;
        test)
            check_cluster_status
            test_failover
            ;;
        *)
            echo "用法: $0 {start|stop|restart|status|test}"
            echo "  start  - 启动哨兵模式"
            echo "  stop   - 停止哨兵模式"
            echo "  restart- 重启哨兵模式"
            echo "  status - 查看集群状态"
            echo "  test   - 测试故障转移"
            exit 1
            ;;
    esac
}

# 运行主函数
main "$@"
